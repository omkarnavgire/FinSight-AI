package com.finsight.transactions.service;

import com.finsight.transactions.domain.Category;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword-based automatic categorisation (T-27, FR-03-7). Stateless and deterministic.
 *
 * <p>Rules, in order (approved keyword table, decision D-6):
 * <ol>
 *   <li>Positive amount → {@link Category#INCOME}.</li>
 *   <li>First category whose keyword appears in the description as a whole word or phrase,
 *       case-insensitive, checked in the order below.</li>
 *   <li>No match, or no description → {@link Category#OTHER}.</li>
 * </ol>
 * Whole-word matching avoids false hits such as "bus" inside "business".
 */
@Component
public class CategoryResolver {

    private static final Map<Category, List<Pattern>> RULES = buildRules();

    private static Map<Category, List<Pattern>> buildRules() {
        var rules = new LinkedHashMap<Category, List<String>>();
        rules.put(Category.GROCERIES, List.of("grocery", "supermarket", "market", "bakery"));
        rules.put(Category.TRANSPORT, List.of("uber", "taxi", "bus", "train", "metro", "fuel", "parking"));
        rules.put(Category.UTILITIES, List.of("electric", "water bill", "gas bill", "internet", "phone", "utility"));
        rules.put(Category.DINING, List.of("restaurant", "cafe", "coffee", "pizza", "burger"));
        rules.put(Category.ENTERTAINMENT, List.of("cinema", "movie", "netflix", "spotify", "concert"));

        var compiled = new LinkedHashMap<Category, List<Pattern>>();
        rules.forEach((category, words) -> compiled.put(category, words.stream()
                .map(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b"))
                .toList()));
        return compiled;
    }

    /**
     * Resolves a category for a new transaction that has no user-supplied category.
     *
     * @param description optional free text; may be {@code null}
     * @param amount      signed amount; must not be {@code null}
     * @return the resolved category, never {@code null}
     */
    public Category resolve(String description, BigDecimal amount) {
        if (amount.signum() > 0) {
            return Category.INCOME;
        }
        if (description == null || description.isBlank()) {
            return Category.OTHER;
        }
        var text = description.toLowerCase(Locale.ROOT);
        for (var rule : RULES.entrySet()) {
            for (var pattern : rule.getValue()) {
                if (pattern.matcher(text).find()) {
                    return rule.getKey();
                }
            }
        }
        return Category.OTHER;
    }
}
