package com.finsight.fraud;

import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.service.FraudDetectionService;
import com.finsight.fraud.service.FraudHistory;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Module-boundary rules for the fraud module (Phase 9, Q-1). */
class FraudModuleBoundaryTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsight");

    @Test
    @DisplayName("fraud depends on no other module except shared (it never imports transactions)")
    void fraud_dependsOnlyOnShared() {
        noClasses().that().resideInAPackage("com.finsight.fraud..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.finsight.auth..", "com.finsight.accounts..", "com.finsight.transactions..",
                        "com.finsight.budgeting..", "com.finsight.analytics..", "com.finsight.cashflow.."))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("transactions uses fraud only via FraudDetectionService, FraudHistory, fraud.dto and FraudRuleName")
    void transactions_useFraudPublicApiOnly() {
        var allowed = resideInAPackage("com.finsight.fraud.dto..")
                .or(belongToAnyOf(FraudDetectionService.class, FraudHistory.class, FraudRuleName.class));

        noClasses().that().resideInAPackage("com.finsight.transactions..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.fraud..").and(not(allowed)))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("Only transactions depends on fraud (and the dev-only devdata seeder)")
    void onlyTransactionsDependOnFraud() {
        noClasses().that().resideOutsideOfPackages(
                        "com.finsight.fraud..", "com.finsight.transactions..", "com.finsight.devdata..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.fraud..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No public fraud service or controller method has a user-ID parameter")
    void noUserIdParameters() {
        var names = Stream.of(
                        "com.finsight.fraud.service.FraudDetectionService",
                        "com.finsight.fraud.service.FraudAlertService",
                        "com.finsight.fraud.controller.FraudAlertController")
                .map(FraudModuleBoundaryTest::load)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .map(p -> p.getName().toLowerCase(Locale.ROOT));

        assertThat(names).noneMatch(n -> n.contains("userid"));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
