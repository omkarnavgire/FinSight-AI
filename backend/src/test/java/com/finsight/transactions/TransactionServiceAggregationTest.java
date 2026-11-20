package com.finsight.transactions;

import com.finsight.shared.exception.BadRequestException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.CategorySpendingDto;
import com.finsight.transactions.dto.DatedAmountDto;
import com.finsight.transactions.dto.MonthSpendingDto;
import com.finsight.transactions.repository.TransactionRepository;
import com.finsight.transactions.repository.TransactionRepository.CategoryTotalRow;
import com.finsight.transactions.repository.TransactionRepository.DatedAmountRow;
import com.finsight.transactions.repository.TransactionRepository.MonthTotalRow;
import com.finsight.transactions.service.TransactionAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TransactionAggregationService} (Phase 7): the user comes from
 * the security context, rows are mapped to DTOs, and invalid ranges are rejected before any SQL (D-11).
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceAggregationTest {

    private static final long CALLER = 21L;
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @Mock private TransactionRepository transactionRepository;

    private TransactionAggregationService service;

    @BeforeEach
    void setUp() {
        service = new TransactionAggregationService(transactionRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static CategoryTotalRow categoryRow(String category, String total) {
        return new CategoryTotalRow() {
            @Override public String getCategory() { return category; }
            @Override public BigDecimal getTotal() { return new BigDecimal(total); }
        };
    }

    private static MonthTotalRow monthRow(Number year, Number month, String total) {
        return new MonthTotalRow() {
            @Override public Number getPeriodYear() { return year; }
            @Override public Number getPeriodMonth() { return month; }
            @Override public BigDecimal getTotal() { return new BigDecimal(total); }
        };
    }

    private void assertRejectedWithoutSql(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(transactionRepository);
    }

    // ---- by category ----

    @Test
    @DisplayName("Category totals query uses the security-context user and the given range")
    void byCategory_usesContextUser() {
        when(transactionRepository.debitTotalsByCategory(CALLER, FROM, TO)).thenReturn(List.of());

        service.debitTotalsByCategory(FROM, TO);

        verify(transactionRepository).debitTotalsByCategory(CALLER, FROM, TO);
    }

    @Test
    @DisplayName("Category rows are mapped to the enum and total, keeping the repository order")
    void byCategory_mapsRows() {
        when(transactionRepository.debitTotalsByCategory(CALLER, FROM, TO))
                .thenReturn(List.of(categoryRow("DINING", "120.50"), categoryRow("GROCERIES", "80.00")));

        var result = service.debitTotalsByCategory(FROM, TO);

        assertThat(result).containsExactly(
                new CategorySpendingDto(Category.DINING, new BigDecimal("120.50")),
                new CategorySpendingDto(Category.GROCERIES, new BigDecimal("80.00")));
    }

    @Test
    @DisplayName("No category rows gives an empty list")
    void byCategory_empty() {
        when(transactionRepository.debitTotalsByCategory(CALLER, FROM, TO)).thenReturn(List.of());

        assertThat(service.debitTotalsByCategory(FROM, TO)).isEmpty();
    }

    // ---- by month ----

    @Test
    @DisplayName("Month rows are mapped from any Number type, using the security-context user")
    void byMonth_mapsRows() {
        when(transactionRepository.debitTotalsByMonth(CALLER, FROM, TO))
                .thenReturn(List.of(monthRow(2025L, 12, "10.00"), monthRow(BigDecimal.valueOf(2026), 1L, "20.00")));

        var result = service.debitTotalsByMonth(FROM, TO);

        assertThat(result).containsExactly(
                new MonthSpendingDto(2025, 12, new BigDecimal("10.00")),
                new MonthSpendingDto(2026, 1, new BigDecimal("20.00")));
    }

    // ---- range validation (D-11) ----

    @Test
    @DisplayName("Missing from or to is 400 and runs no SQL")
    void nullBounds_rejected() {
        assertRejectedWithoutSql(() -> service.debitTotalsByCategory(null, TO));
        assertRejectedWithoutSql(() -> service.debitTotalsByMonth(FROM, null));
    }

    @Test
    @DisplayName("from after to is 400 and runs no SQL")
    void fromAfterTo_rejected() {
        assertRejectedWithoutSql(() -> service.debitTotalsByCategory(TO, FROM));
        assertRejectedWithoutSql(() -> service.debitTotalsByMonth(TO, FROM));
    }

    @Test
    @DisplayName("A range of 367 days inclusive is 400 and runs no SQL")
    void range367_rejected() {
        var from = LocalDate.of(2025, 1, 1);
        var to = from.plusDays(366); // 367 days counting both ends

        assertRejectedWithoutSql(() -> service.debitTotalsByCategory(from, to));
        assertRejectedWithoutSql(() -> service.debitTotalsByMonth(from, to));
    }

    @Test
    @DisplayName("A range of exactly 366 days inclusive is allowed")
    void range366_allowed() {
        var from = LocalDate.of(2024, 1, 1);
        var to = LocalDate.of(2024, 12, 31); // leap year: 366 days
        when(transactionRepository.debitTotalsByCategory(anyLong(), any(), any())).thenReturn(List.of());
        when(transactionRepository.debitTotalsByMonth(anyLong(), any(), any())).thenReturn(List.of());

        service.debitTotalsByCategory(from, to);
        service.debitTotalsByMonth(from, to);

        verify(transactionRepository).debitTotalsByCategory(CALLER, from, to);
        verify(transactionRepository).debitTotalsByMonth(CALLER, from, to);
    }

    @Test
    @DisplayName("A single-day range is allowed")
    void singleDay_allowed() {
        when(transactionRepository.debitTotalsByCategory(CALLER, FROM, FROM)).thenReturn(List.of());

        assertThat(service.debitTotalsByCategory(FROM, FROM)).isEmpty();
    }

    // ---- dated amounts (Phase 8) ----

    private static DatedAmountRow datedRow(Object date, String amount) {
        return new DatedAmountRow() {
            @Override public Object getTransactionDate() { return date; }
            @Override public BigDecimal getAmount() { return new BigDecimal(amount); }
        };
    }

    @Test
    @DisplayName("Dated amounts use the security-context user and keep signs and order; both date types are mapped")
    void amounts_mapsRows() {
        when(transactionRepository.amountsBetween(CALLER, FROM, TO)).thenReturn(List.of(
                datedRow(LocalDate.of(2026, 3, 1), "3000.00"),
                datedRow(java.sql.Date.valueOf("2026-03-02"), "-45.10")));

        var result = service.amountsBetween(FROM, TO);

        assertThat(result).containsExactly(
                new DatedAmountDto(LocalDate.of(2026, 3, 1), new BigDecimal("3000.00")),
                new DatedAmountDto(LocalDate.of(2026, 3, 2), new BigDecimal("-45.10")));
    }

    @Test
    @DisplayName("Dated amounts reject an invalid or too-long range with 400 and run no SQL")
    void amounts_invalidRange_rejected() {
        assertRejectedWithoutSql(() -> service.amountsBetween(null, TO));
        assertRejectedWithoutSql(() -> service.amountsBetween(TO, FROM));
        assertRejectedWithoutSql(() -> service.amountsBetween(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)));
    }
}
