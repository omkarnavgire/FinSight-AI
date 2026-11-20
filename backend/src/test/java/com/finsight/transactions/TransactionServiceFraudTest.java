package com.finsight.transactions;

import com.finsight.accounts.service.AccountService;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.dto.FraudCandidate;
import com.finsight.fraud.service.FraudDetectionService;
import com.finsight.shared.exception.AccountNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.repository.TransactionRepository;
import com.finsight.transactions.service.CategoryResolver;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fraud wiring in {@link TransactionService#create} (T-54, design.md ordering): rules run after the
 * checks and before the INSERT; alerts are recorded after the balance update for the saved ID.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceFraudTest {

    private static final long CALLER = 71L;
    private static final long ACCOUNT = 8L;
    private static final long SAVED_ID = 123L;

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountService accountService;
    @Mock private CategoryResolver categoryResolver;
    @Mock private FraudDetectionService fraudDetection;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(transactionRepository, accountService, categoryResolver, fraudDetection);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static CreateTransactionRequest request(Category category) {
        return new CreateTransactionRequest(ACCOUNT, new BigDecimal("-40.00"), LocalDate.of(2026, 3, 10),
                category, "Taxi home", "GB");
    }

    private void givenSaveAssignsId() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", SAVED_ID);
            return t;
        });
    }

    @Test
    @DisplayName("Order: account check → rules → INSERT → balance → alerts for the saved ID")
    void create_order() {
        givenSaveAssignsId();
        var fired = List.of(FraudRuleName.UNSEEN_COUNTRY);
        when(fraudDetection.evaluate(any())).thenReturn(fired);

        service.create(request(Category.TRANSPORT));

        var order = inOrder(accountService, fraudDetection, transactionRepository);
        order.verify(accountService).requireOwnedActive(ACCOUNT);
        order.verify(fraudDetection).evaluate(any());
        order.verify(transactionRepository).save(any(Transaction.class));
        order.verify(accountService).applyDelta(eq(ACCOUNT), any());
        order.verify(fraudDetection).recordAlerts(SAVED_ID, fired);
    }

    @Test
    @DisplayName("The candidate carries account, amount, resolved category name, description and country")
    void create_candidateUsesResolvedCategory() {
        givenSaveAssignsId();
        when(categoryResolver.resolve("Taxi home", new BigDecimal("-40.00"))).thenReturn(Category.TRANSPORT);
        when(fraudDetection.evaluate(any())).thenReturn(List.of());

        service.create(request(null));

        verify(fraudDetection).evaluate(new FraudCandidate(
                ACCOUNT, new BigDecimal("-40.00"), "TRANSPORT", "Taxi home", "GB"));
    }

    @Test
    @DisplayName("A failed account check runs no rules and writes nothing")
    void create_accountCheckFails_noRules() {
        doThrow(new AccountNotFoundException(ACCOUNT)).when(accountService).requireOwnedActive(ACCOUNT);

        assertThatThrownBy(() -> service.create(request(Category.TRANSPORT)))
                .isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(fraudDetection);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("An alert-recording failure is not caught: create() fails, so its transaction rolls back")
    void create_alertFailure_propagates() {
        givenSaveAssignsId();
        var fired = List.of(FraudRuleName.DUPLICATE_TRANSACTION);
        when(fraudDetection.evaluate(any())).thenReturn(fired);
        doThrow(new IllegalStateException("simulated")).when(fraudDetection).recordAlerts(SAVED_ID, fired);

        assertThatThrownBy(() -> service.create(request(Category.TRANSPORT)))
                .isInstanceOf(IllegalStateException.class);
    }
}
