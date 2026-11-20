package com.finsight.cashflow;

import com.finsight.accounts.service.AccountService;
import com.finsight.auth.service.UserDirectory;
import com.finsight.transactions.service.TransactionAggregationService;
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

/** Module-boundary rules for the cash-flow module (Phase 8). */
class CashFlowModuleBoundaryTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsight");

    @Test
    @DisplayName("cashflow uses auth only through UserDirectory")
    void auth_onlyUserDirectory() {
        noClasses().that().resideInAPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.auth..")
                        .and(not(belongToAnyOf(UserDirectory.class))))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("cashflow uses accounts only through AccountService and accounts.dto")
    void accounts_onlyPublicApi() {
        noClasses().that().resideInAPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.accounts..")
                        .and(not(resideInAPackage("com.finsight.accounts.dto..")
                                .or(belongToAnyOf(AccountService.class)))))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("cashflow uses transactions only through TransactionAggregationService and transactions.dto")
    void transactions_onlyAggregationApi() {
        noClasses().that().resideInAPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.transactions..")
                        .and(not(resideInAPackage("com.finsight.transactions.dto..")
                                .or(belongToAnyOf(TransactionAggregationService.class)))))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("cashflow never depends on budgeting, analytics or another module's repository")
    void noOtherModulesOrRepositories() {
        noClasses().that().resideInAPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat(resideInAnyPackage(
                        "com.finsight.budgeting..", "com.finsight.analytics..",
                        "com.finsight.auth.repository..", "com.finsight.accounts.repository..",
                        "com.finsight.transactions.repository.."))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No other module depends on cashflow")
    void nothingDependsOnCashflow() {
        noClasses().that().resideOutsideOfPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.cashflow..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No public cash-flow service or controller method takes a numeric ID or a user-ID parameter")
    void noUserIdParameters() {
        var methods = Stream.of(
                        "com.finsight.cashflow.service.CashFlowService",
                        "com.finsight.cashflow.controller.CashFlowController")
                .map(CashFlowModuleBoundaryTest::load)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .toList();

        assertThat(methods.stream().flatMap(m -> Arrays.stream(m.getParameterTypes())))
                .noneMatch(t -> t == Long.class || t == long.class || t == Integer.class || t == int.class);
        assertThat(methods.stream().flatMap(m -> Arrays.stream(m.getParameters()))
                .map(p -> p.getName().toLowerCase(Locale.ROOT)))
                .noneMatch(n -> n.contains("userid"));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
