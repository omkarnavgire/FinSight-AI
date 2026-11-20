package com.finsight.analytics;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.service.TransactionAggregationService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Module-boundary rules for the analytics module (Phase 7, safeguards 5 and 6). */
class AnalyticsModuleBoundaryTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsight");

    @Test
    @DisplayName("analytics uses transactions only via TransactionAggregationService, transactions.dto and Category")
    void analytics_usesTransactionsPublicApiOnly() {
        var allowed = resideInAPackage("com.finsight.transactions.dto..")
                .or(belongToAnyOf(TransactionAggregationService.class, Category.class));

        noClasses().that().resideInAPackage("com.finsight.analytics..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.transactions..").and(not(allowed)))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("analytics never depends on accounts, budgeting or any repository package")
    void analytics_doesNotDependOnAccountsBudgetingOrRepositories() {
        noClasses().that().resideInAPackage("com.finsight.analytics..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.finsight.accounts..", "com.finsight.budgeting..", "..repository.."))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No other module depends on analytics")
    void nothingDependsOnAnalytics() {
        noClasses().that().resideOutsideOfPackage("com.finsight.analytics..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.analytics..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("Analytics service and controller methods take no numeric ID parameter")
    void noIdParameterTypes() {
        var types = Stream.of(
                        "com.finsight.analytics.service.AnalyticsService",
                        "com.finsight.analytics.controller.AnalyticsController")
                .map(this::load)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .flatMap(m -> Arrays.stream(m.getParameterTypes()));

        assertThat(types).noneMatch(t -> t == Long.class || t == long.class || t == Integer.class || t == int.class);
    }

    @Test
    @DisplayName("No analytics method parameter or DTO field is named like a user ID")
    void noUserIdExposed() {
        var classes = Stream.of(
                "com.finsight.analytics.service.AnalyticsService",
                "com.finsight.analytics.controller.AnalyticsController",
                "com.finsight.analytics.service.AnalyticsParams");
        var paramNames = classes.map(this::load)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .flatMap(AnalyticsModuleBoundaryTest::paramNames);
        var fieldNames = Stream.of(
                        "com.finsight.analytics.dto.CategoryBreakdownDto",
                        "com.finsight.analytics.dto.CategoryBreakdownDto$CategoryAmount",
                        "com.finsight.analytics.dto.MonthlyTrendDto",
                        "com.finsight.analytics.dto.MonthlyTrendDto$MonthAmount")
                .map(this::load)
                .flatMap(c -> Arrays.stream(c.getRecordComponents()))
                .map(RecordComponent::getName);

        assertThat(Stream.concat(paramNames, fieldNames).map(n -> n.toLowerCase(Locale.ROOT)))
                .noneMatch(n -> n.contains("userid") || n.equals("user"));
    }

    private static Stream<String> paramNames(Method method) {
        return Arrays.stream(method.getParameters()).map(p -> p.getName());
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
