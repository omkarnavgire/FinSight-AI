package com.finsight.transactions;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.service.CategoryResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CategoryResolver} (T-27) against the approved keyword table.
 */
class CategoryResolverTest {

    private static final BigDecimal DEBIT = new BigDecimal("-12.00");

    private final CategoryResolver resolver = new CategoryResolver();

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "Weekly grocery run, GROCERIES",
            "City Supermarket, GROCERIES",
            "Farmers market, GROCERIES",
            "Corner bakery, GROCERIES",
            "Uber ride, TRANSPORT",
            "Taxi to airport, TRANSPORT",
            "Bus pass, TRANSPORT",
            "Train ticket, TRANSPORT",
            "Metro card, TRANSPORT",
            "Fuel top-up, TRANSPORT",
            "Airport parking, TRANSPORT",
            "Electric company, UTILITIES",
            "Water bill March, UTILITIES",
            "Gas bill, UTILITIES",
            "Internet provider, UTILITIES",
            "Phone plan, UTILITIES",
            "City utility charge, UTILITIES",
            "Italian restaurant, DINING",
            "Cafe latte, DINING",
            "Coffee beans, DINING",
            "Pizza night, DINING",
            "Burger bar, DINING",
            "Cinema tickets, ENTERTAINMENT",
            "Movie rental, ENTERTAINMENT",
            "Netflix subscription, ENTERTAINMENT",
            "Spotify premium, ENTERTAINMENT",
            "Concert entry, ENTERTAINMENT"
    })
    @DisplayName("Each approved keyword maps to its category")
    void keywordsMapToCategory(String description, Category expected) {
        assertThat(resolver.resolve(description, DEBIT)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Matching is case-insensitive")
    void caseInsensitive() {
        assertThat(resolver.resolve("NETFLIX", DEBIT)).isEqualTo(Category.ENTERTAINMENT);
    }

    @Test
    @DisplayName("Positive amount is INCOME regardless of description")
    void positiveAmount_isIncome() {
        assertThat(resolver.resolve("Pizza refund", new BigDecimal("15.00"))).isEqualTo(Category.INCOME);
    }

    @Test
    @DisplayName("No keyword match falls back to OTHER")
    void noMatch_isOther() {
        assertThat(resolver.resolve("Hardware store", DEBIT)).isEqualTo(Category.OTHER);
    }

    @Test
    @DisplayName("Null or blank description falls back to OTHER")
    void nullOrBlank_isOther() {
        assertThat(resolver.resolve(null, DEBIT)).isEqualTo(Category.OTHER);
        assertThat(resolver.resolve("   ", DEBIT)).isEqualTo(Category.OTHER);
    }

    @Test
    @DisplayName("Keywords match whole words only: 'business' does not match 'bus'")
    void wholeWordsOnly() {
        assertThat(resolver.resolve("Business lunch at Harbor", DEBIT)).isEqualTo(Category.OTHER);
    }

    @Test
    @DisplayName("First category in table order wins when several match")
    void tableOrderWins() {
        // "market" (GROCERIES) is checked before "coffee" (DINING)
        assertThat(resolver.resolve("Market coffee stall", DEBIT)).isEqualTo(Category.GROCERIES);
    }
}
