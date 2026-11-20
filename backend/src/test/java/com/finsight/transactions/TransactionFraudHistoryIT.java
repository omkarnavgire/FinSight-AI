package com.finsight.transactions;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.fraud.service.FraudHistory;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TransactionFraudHistory} queries on MySQL 8 (Phase 9, Q-1, Q-4, Q-6, C-1, C-2):
 * owner scoping, null-safe description match, category totals and country history.
 */
@SpringBootTest
@Testcontainers
class TransactionFraudHistoryIT {

    private static final AtomicLong USERS = new AtomicLong(11000);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("finsight_fraud_history_it")
            .withUsername("finsight")
            .withPassword("finsight_it_pw");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("finsight.security.jwt.secret", () -> "fraud-history-it-secret-must-be-at-least-32-chars!");
        registry.add("finsight.security.cookie.secure", () -> "false");
    }

    @Autowired private FraudHistory history;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void actAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private long openAccount(long owner) {
        actAs(owner);
        return accountService.create(new CreateAccountRequest("IT", AccountType.CHECKING, new BigDecimal("5000.00"), null)).id();
    }

    private void create(long accountId, String amount, LocalDate date, Category category, String description, String country) {
        transactionService.create(new CreateTransactionRequest(
                accountId, new BigDecimal(amount), date, category, description, country));
    }

    @Test
    @DisplayName("Duplicate lookup: matches amount and description, null matches null, other users excluded; createdAt round-trips in the JVM zone")
    void duplicateLookup() {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long accA = openAccount(a);
        create(accA, "-12.50", LocalDate.now(), Category.DINING, "Lunch", "US");
        create(accA, "-12.50", LocalDate.now(), Category.DINING, null, "US");
        long accB = openAccount(b);
        create(accB, "-12.50", LocalDate.now(), Category.DINING, "Lunch", "US");

        actAs(a);
        var latest = history.latestDuplicateCreatedAt(accA, new BigDecimal("-12.5"), "Lunch");
        var nullMatch = history.latestDuplicateCreatedAt(accA, new BigDecimal("-12.50"), null);
        var otherAmount = history.latestDuplicateCreatedAt(accA, new BigDecimal("-12.51"), "Lunch");
        var foreignAccount = history.latestDuplicateCreatedAt(accB, new BigDecimal("-12.50"), "Lunch");

        assertThat(latest).isPresent();
        assertThat(Duration.between(latest.orElseThrow(), LocalDateTime.now()).abs())
                .as("created_at must compare correctly with LocalDateTime.now() (C-1)")
                .isLessThan(Duration.ofMinutes(1));
        assertThat(nullMatch).isPresent();
        assertThat(otherAmount).isEmpty();
        assertThat(foreignAccount).as("another user's account is never visible").isEmpty();
    }

    @Test
    @DisplayName("Category stats: count and signed sum across all accounts (deactivated included), both window ends inclusive")
    void categoryStats() {
        long a = USERS.incrementAndGet();
        long acc1 = openAccount(a);
        long acc2 = openAccount(a);
        var today = LocalDate.now();
        create(acc1, "-10.00", today.minusDays(29), Category.DINING, "d1", "US");   // first day of window
        create(acc1, "-20.00", today, Category.DINING, "d2", "US");                  // last day
        create(acc2, "5.00", today.minusDays(3), Category.DINING, "refund", "US");   // credit, signed
        create(acc1, "-99.00", today.minusDays(30), Category.DINING, "old", "US");   // outside window
        create(acc1, "-99.00", today, Category.OTHER, "other", "US");                // other category
        actAs(a);
        accountService.deactivate(acc2);
        long b = USERS.incrementAndGet();
        create(openAccount(b), "-500.00", today, Category.DINING, "foreign", "US");

        actAs(a);
        var stats = history.categoryStats("DINING", today.minusDays(29), today);

        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualByComparingTo("-25.00");
    }

    @Test
    @DisplayName("Country history: any account (deactivated included), any time; another user's countries do not count")
    void countryHistory() {
        long a = USERS.incrementAndGet();
        long b = USERS.incrementAndGet();
        long fresh = USERS.incrementAndGet();
        long accA = openAccount(a);
        create(accA, "-1.00", LocalDate.of(2025, 1, 1), Category.OTHER, "x", "GB");
        actAs(a);
        accountService.deactivate(accA);
        create(openAccount(b), "-1.00", LocalDate.now(), Category.OTHER, "x", "JP");

        actAs(a);
        assertThat(history.hasAnyTransaction()).isTrue();
        assertThat(history.hasUsedCountry("GB")).isTrue();
        assertThat(history.hasUsedCountry("JP")).isFalse();
        actAs(fresh);
        assertThat(history.hasAnyTransaction()).isFalse();
    }
}
