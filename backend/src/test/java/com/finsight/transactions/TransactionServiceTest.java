package com.finsight.transactions;

import com.finsight.accounts.service.AccountService;
import com.finsight.shared.exception.AccountDeactivatedException;
import com.finsight.shared.exception.AccountNotFoundException;
import com.finsight.shared.exception.BadRequestException;
import com.finsight.shared.exception.InvalidCountryCodeException;
import com.finsight.shared.exception.TransactionNotEditableException;
import com.finsight.shared.exception.TransactionNotFoundException;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.domain.Transaction;
import com.finsight.transactions.dto.CreateTransactionRequest;
import com.finsight.transactions.dto.TransactionFilter;
import com.finsight.transactions.dto.UpdateTransactionRequest;
import com.finsight.transactions.repository.TransactionRepository;
import com.finsight.transactions.service.CategoryResolver;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TransactionService} (T-30). No database: the repository,
 * {@link AccountService} and {@link CategoryResolver} are mocked. Caller is user {@value #CALLER}.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final long CALLER = 11L;
    private static final long ACCOUNT = 5L;
    private static final long TX_ID = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 1, 15);

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountService accountService;
    @Mock private CategoryResolver categoryResolver;

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(transactionRepository, accountService, categoryResolver);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CALLER, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static CreateTransactionRequest createReq(String amount, Category category, String country) {
        return new CreateTransactionRequest(ACCOUNT, new BigDecimal(amount), DAY, category, "Coffee shop", country);
    }

    private static Transaction existing(String amount) {
        return Transaction.manual(ACCOUNT, new BigDecimal(amount), Category.DINING, "Lunch", DAY, "US");
    }

    private static BigDecimal eqAmount(String expected) {
        return argThat(v -> v != null && v.compareTo(new BigDecimal(expected)) == 0);
    }

    // ---- create ----

    @Test
    @DisplayName("Unknown country code returns 400 before any repository or account call")
    void create_unknownCountry_rejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(createReq("-10.00", Category.DINING, "ZZ")))
                .isInstanceOf(InvalidCountryCodeException.class);
        verifyNoInteractions(transactionRepository, accountService);
    }

    @Test
    @DisplayName("Zero amount returns 400 before any write")
    void create_zeroAmount_rejected() {
        assertThatThrownBy(() -> service.create(createReq("0.00", Category.DINING, "US")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(transactionRepository, accountService);
    }

    @Test
    @DisplayName("Create on another user's account returns 404 and inserts nothing")
    void create_foreignAccount_notFound_noInsert() {
        when(accountService.requireOwnedActive(ACCOUNT)).thenThrow(new AccountNotFoundException(ACCOUNT));

        assertThatThrownBy(() -> service.create(createReq("-10.00", Category.DINING, "US")))
                .isInstanceOf(AccountNotFoundException.class);
        verify(transactionRepository, never()).save(any());
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Create on a deactivated account returns 409 and inserts nothing")
    void create_deactivatedAccount_conflict_noInsert() {
        when(accountService.requireOwnedActive(ACCOUNT)).thenThrow(new AccountDeactivatedException(ACCOUNT));

        assertThatThrownBy(() -> service.create(createReq("-10.00", Category.DINING, "US")))
                .isInstanceOf(AccountDeactivatedException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Create checks the account, inserts, then applies +amount exactly once, in that order")
    void create_orderAndSingleBalanceUpdate() {
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(createReq("-50.00", Category.DINING, "US"));

        InOrder order = inOrder(accountService, transactionRepository);
        order.verify(accountService).requireOwnedActive(ACCOUNT);
        order.verify(transactionRepository).save(any());
        order.verify(accountService).applyDelta(eq(ACCOUNT), eqAmount("-50.00"));
        verify(accountService, times(1)).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Create marks the transaction as manual and stores the request fields")
    void create_marksManual() {
        var captor = ArgumentCaptor.forClass(Transaction.class);
        when(transactionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(createReq("-50.00", Category.DINING, "GB"));

        assertThat(captor.getValue().isManual()).isTrue();
        assertThat(captor.getValue().getCountryCode()).isEqualTo("GB");
        assertThat(dto.accountId()).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("Create without a category uses CategoryResolver")
    void create_noCategory_usesResolver() {
        when(categoryResolver.resolve("Coffee shop", new BigDecimal("-4.50"))).thenReturn(Category.DINING);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(createReq("-4.50", null, "US"));

        assertThat(dto.category()).isEqualTo(Category.DINING);
    }

    @Test
    @DisplayName("Create with a category does not call CategoryResolver")
    void create_withCategory_skipsResolver() {
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(createReq("-4.50", Category.ENTERTAINMENT, "US"));

        verifyNoInteractions(categoryResolver);
    }

    // ---- update ----

    @Test
    @DisplayName("Update of a missing or foreign transaction returns 404 with no balance change")
    void update_foreign_notFound() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(TX_ID, new UpdateTransactionRequest(BigDecimal.TEN, DAY, Category.OTHER, null)))
                .isInstanceOf(TransactionNotFoundException.class);
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Update -50 to -80 applies the net delta -30 exactly once")
    void update_appliesNetDeltaOnce() {
        var tx = existing("-50.00");
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        var dto = service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-80.00"), DAY, Category.DINING, "Dinner"));

        verify(accountService, times(1)).applyDelta(eq(ACCOUNT), eqAmount("-30.00"));
        assertThat(dto.amount()).isEqualByComparingTo("-80.00");
        assertThat(dto.description()).isEqualTo("Dinner");
    }

    @Test
    @DisplayName("Update edits the entity before calling applyDelta (persistence-context rule)")
    void update_editsBeforeApplyDelta() {
        var tx = existing("-50.00");
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));
        doAnswer(inv -> {
            assertThat(tx.getAmount()).isEqualByComparingTo("-80.00");
            return null;
        }).when(accountService).applyDelta(anyLong(), any());

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-80.00"), DAY, Category.DINING, null));

        verify(accountService).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Update with an unchanged amount still calls applyDelta(0) once")
    void update_sameAmount_zeroDelta() {
        var tx = existing("-50.00");
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        service.update(TX_ID, new UpdateTransactionRequest(new BigDecimal("-50.00"), DAY, Category.OTHER, "x"));

        verify(accountService, times(1)).applyDelta(eq(ACCOUNT), eqAmount("0"));
    }

    @Test
    @DisplayName("Update of a non-manual transaction returns 409 with no balance change")
    void update_nonManual_conflict() {
        var tx = existing("-50.00");
        ReflectionTestUtils.setField(tx, "manual", false);
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> service.update(TX_ID, new UpdateTransactionRequest(BigDecimal.ONE, DAY, Category.OTHER, null)))
                .isInstanceOf(TransactionNotEditableException.class);
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Update on a deactivated account returns 409 with no balance change")
    void update_deactivated_conflict() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(existing("-5.00")));
        when(accountService.requireOwnedActive(ACCOUNT)).thenThrow(new AccountDeactivatedException(ACCOUNT));

        assertThatThrownBy(() -> service.update(TX_ID, new UpdateTransactionRequest(BigDecimal.ONE, DAY, Category.OTHER, null)))
                .isInstanceOf(AccountDeactivatedException.class);
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Update to a zero amount returns 400")
    void update_zeroAmount_rejected() {
        assertThatThrownBy(() -> service.update(TX_ID, new UpdateTransactionRequest(BigDecimal.ZERO, DAY, Category.OTHER, null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(transactionRepository, accountService);
    }

    // ---- delete ----

    @Test
    @DisplayName("Delete of -50 deletes the row then applies +50 exactly once")
    void delete_reversesAmountOnce() {
        var tx = existing("-50.00");
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        service.delete(TX_ID);

        InOrder order = inOrder(transactionRepository, accountService);
        order.verify(transactionRepository).delete(tx);
        order.verify(accountService).applyDelta(eq(ACCOUNT), eqAmount("50.00"));
        verify(accountService, times(1)).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Delete of a missing or foreign transaction returns 404 and deletes nothing")
    void delete_foreign_notFound() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TX_ID)).isInstanceOf(TransactionNotFoundException.class);
        verify(transactionRepository, never()).delete(any());
        verify(accountService, never()).applyDelta(anyLong(), any());
    }

    @Test
    @DisplayName("Delete of a non-manual transaction returns 409 and deletes nothing")
    void delete_nonManual_conflict() {
        var tx = existing("-50.00");
        ReflectionTestUtils.setField(tx, "manual", false);
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> service.delete(TX_ID)).isInstanceOf(TransactionNotEditableException.class);
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Delete on a deactivated account returns 409 and deletes nothing")
    void delete_deactivated_conflict() {
        when(transactionRepository.findByIdAndUserIdForUpdate(TX_ID, CALLER)).thenReturn(Optional.of(existing("-5.00")));
        when(accountService.requireOwnedActive(ACCOUNT)).thenThrow(new AccountDeactivatedException(ACCOUNT));

        assertThatThrownBy(() -> service.delete(TX_ID)).isInstanceOf(AccountDeactivatedException.class);
        verify(transactionRepository, never()).delete(any());
    }

    // ---- category override ----

    @Test
    @DisplayName("Category override changes the category and never touches the balance")
    void overrideCategory_noBalanceChange() {
        var tx = existing("-50.00");
        when(transactionRepository.findByIdAndUserId(TX_ID, CALLER)).thenReturn(Optional.of(tx));

        var dto = service.overrideCategory(TX_ID, Category.GROCERIES);

        assertThat(dto.category()).isEqualTo(Category.GROCERIES);
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("Category override on a foreign transaction returns 404")
    void overrideCategory_foreign_notFound() {
        when(transactionRepository.findByIdAndUserId(TX_ID, CALLER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.overrideCategory(TX_ID, Category.OTHER))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    // ---- search ----

    @Test
    @DisplayName("Search with another user's accountId returns 404 and runs no query")
    void search_foreignAccount_notFound() {
        when(accountService.getMine(ACCOUNT)).thenThrow(new AccountNotFoundException(ACCOUNT));
        var filter = new TransactionFilter(ACCOUNT, null, null, null, null, null);

        assertThatThrownBy(() -> service.search(filter, 0, 20)).isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Search caps page size at 100 and scopes the query to the caller")
    void search_capsSize_andScopesToCaller() {
        Page<Transaction> empty = new PageImpl<>(List.of());
        when(transactionRepository.search(eq(CALLER), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(empty);

        service.search(TransactionFilter.none(), 0, 500);

        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).search(eq(CALLER), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Search rejects inverted date and amount ranges and invalid paging with 400")
    void search_invalidInput_badRequest() {
        assertThatThrownBy(() -> service.search(new TransactionFilter(null, DAY, DAY.minusDays(1), null, null, null), 0, 20))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(new TransactionFilter(null, null, null, null, BigDecimal.TEN, BigDecimal.ONE), 0, 20))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(TransactionFilter.none(), -1, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(TransactionFilter.none(), 0, 0)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Search passes the category as its enum name")
    void search_categoryAsName() {
        when(transactionRepository.search(eq(CALLER), isNull(), isNull(), isNull(), eq("DINING"), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.search(new TransactionFilter(null, null, null, Category.DINING, null, null), 0, 20);

        verify(transactionRepository).search(eq(CALLER), isNull(), isNull(), isNull(), eq("DINING"), isNull(), isNull(), any());
    }
}
