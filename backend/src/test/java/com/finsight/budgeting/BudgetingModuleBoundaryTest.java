package com.finsight.budgeting;

import com.finsight.transactions.domain.Category;
import com.finsight.transactions.service.TransactionService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-boundary rules for the budgeting module (Phase 6, scope correction C-2).
 * Kept separate from {@code ModuleBoundaryTest}, which is unchanged.
 */
class BudgetingModuleBoundaryTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsight");

    @Test
    @DisplayName("budgeting uses transactions only via TransactionService, transactions.dto, transactions.event and Category")
    void budgeting_usesTransactionsPublicApiOnly() {
        var allowed = resideInAnyPackage("com.finsight.transactions.dto..", "com.finsight.transactions.event..")
                .or(belongToAnyOf(TransactionService.class, Category.class));

        noClasses().that().resideInAPackage("com.finsight.budgeting..")
                .should().dependOnClassesThat(resideInAPackage("com.finsight.transactions..").and(not(allowed)))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("transactions never depends on budgeting (no dependency cycle)")
    void transactions_doNotDependOnBudgeting() {
        noClasses().that().resideInAPackage("com.finsight.transactions..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.budgeting..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("budgeting never depends on accounts")
    void budgeting_doesNotDependOnAccounts() {
        noClasses().that().resideInAPackage("com.finsight.budgeting..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.accounts..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No class outside budgeting uses budgeting.repository")
    void budgetingRepository_privateToModule() {
        noClasses().that().resideOutsideOfPackage("com.finsight.budgeting..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.budgeting.repository..")
                .check(PRODUCTION);
    }
}
