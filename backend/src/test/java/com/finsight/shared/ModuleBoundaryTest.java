package com.finsight.shared;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit module boundary enforcement (T-19b).
 *
 * <p>Verifies the modular-monolith rule: no class outside a module's own boundary
 * may import that module's {@code repository} package. Cross-module data access
 * must go through a named service interface only.
 *
 * <p>This catches accidental imports that would couple modules at the data-access
 * layer, which would break independent testability and violate AC-FK-2.
 *
 * <p>These are pure bytecode-analysis tests — no Spring context, no database.
 */
class ModuleBoundaryTest {

    private static final com.tngtech.archunit.core.domain.JavaClasses ALL_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.finsight");

    @Test
    @DisplayName("auth module: no outside class imports auth.repository")
    void authRepository_notAccessedFromOutsideAuthModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.auth..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.auth.repository..");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("accounts module: no outside class imports accounts.repository")
    void accountsRepository_notAccessedFromOutsideAccountsModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.accounts..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.accounts.repository..");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("transactions module: no outside class imports transactions.repository")
    void transactionsRepository_notAccessedFromOutsideTransactionsModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.transactions..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.transactions.repository..");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("budgeting module: no outside class imports budgeting.repository")
    void budgetingRepository_notAccessedFromOutsideBudgetingModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.budgeting..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.budgeting.repository..");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("cashflow module: no outside class imports cashflow.repository")
    void cashflowRepository_notAccessedFromOutsideCashflowModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.cashflow..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.cashflow.repository..");
        rule.check(ALL_CLASSES);
    }

    @Test
    @DisplayName("fraud module: no outside class imports fraud.repository")
    void fraudRepository_notAccessedFromOutsideFraudModule() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.finsight.fraud..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.finsight.fraud.repository..");
        rule.check(ALL_CLASSES);
    }
}
