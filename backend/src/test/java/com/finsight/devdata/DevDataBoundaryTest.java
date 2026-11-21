package com.finsight.devdata;

import com.finsight.auth.service.DemoUserSeedService;
import com.finsight.fraud.service.FraudAlertSeedService;
import com.finsight.transactions.service.TransactionSeedService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Boundary and profile rules for the dev-only seeder (Q-2, AC-SEED-1). */
class DevDataBoundaryTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsight");

    @Test
    @DisplayName("devdata never uses a repository or a controller of any module")
    void noRepositoriesOrControllers() {
        noClasses().that().resideInAPackage("com.finsight.devdata..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..controller..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("No production code depends on devdata")
    void nothingDependsOnDevdata() {
        noClasses().that().resideOutsideOfPackage("com.finsight.devdata..")
                .should().dependOnClassesThat().resideInAPackage("com.finsight.devdata..")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("The seeder and every seed service exist only in the dev profile")
    void seedBeansAreDevOnly() {
        assertThat(Stream.of(DataSeeder.class, DemoUserSeedService.class,
                        TransactionSeedService.class, FraudAlertSeedService.class))
                .allSatisfy(c -> assertThat(c.getAnnotation(Profile.class))
                        .as(c.getSimpleName()).isNotNull()
                        .extracting(Profile::value).isEqualTo(new String[]{"dev"}));
    }
}
