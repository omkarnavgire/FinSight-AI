package com.finsight.budgeting;

import com.finsight.budgeting.service.SpendingCalculator;
import com.finsight.shared.pagination.PageResponse;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.dto.TransactionDto;
import com.finsight.transactions.dto.TransactionFilter;
import com.finsight.transactions.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** {@link SpendingCalculator} uses only the public {@code TransactionService.search} API. */
@ExtendWith(MockitoExtension.class)
class SpendingCalculatorTest {

    @Mock private TransactionService transactionService;

    private static TransactionDto debit(String amount) {
        return new TransactionDto(1L, 1L, new BigDecimal(amount), Category.DINING, null,
                LocalDate.of(2026, 3, 1), "US", true, null);
    }

    private static PageResponse<TransactionDto> page(int number, boolean last, TransactionDto... items) {
        return new PageResponse<>(List.of(items), number, 100, 0, 0, last);
    }

    @Test
    @DisplayName("Builds a debits-only filter for the whole month and category")
    void filter_isDebitsOnlyForTheMonth() {
        when(transactionService.search(any(), eq(0), eq(100))).thenReturn(page(0, true));

        new SpendingCalculator(transactionService).debitTotal(Category.DINING, YearMonth.of(2026, 2));

        var captor = ArgumentCaptor.forClass(TransactionFilter.class);
        verify(transactionService).search(captor.capture(), eq(0), eq(100));
        var f = captor.getValue();
        assertThat(f.from()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(f.to()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(f.category()).isEqualTo(Category.DINING);
        assertThat(f.maxAmount()).isEqualByComparingTo("-0.01");
        assertThat(f.minAmount()).isNull();
        assertThat(f.accountId()).isNull();
    }

    @Test
    @DisplayName("Sums the absolute value of debits across all pages")
    void sumsAllPages() {
        when(transactionService.search(any(), eq(0), anyInt())).thenReturn(page(0, false, debit("-10.00"), debit("-2.50")));
        when(transactionService.search(any(), eq(1), anyInt())).thenReturn(page(1, true, debit("-7.50")));

        var total = new SpendingCalculator(transactionService).debitTotal(Category.DINING, YearMonth.of(2026, 3));

        assertThat(total).isEqualByComparingTo("20.00");
        verify(transactionService, times(2)).search(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("No transactions gives zero")
    void empty_isZero() {
        when(transactionService.search(any(), eq(0), anyInt())).thenReturn(page(0, true));

        assertThat(new SpendingCalculator(transactionService).debitTotal(Category.OTHER, YearMonth.of(2026, 3)))
                .isEqualByComparingTo("0");
    }
}
