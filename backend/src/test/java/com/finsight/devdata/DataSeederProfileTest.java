package com.finsight.devdata;

import com.finsight.auth.service.DemoUserSeedService;
import com.finsight.fraud.service.FraudAlertSeedService;
import com.finsight.transactions.service.TransactionSeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-61b (unit half): the seeder and its seed services are registered only under the {@code dev}
 * profile (AC-SEED-1). Profile conditions are evaluated at registration, so no context is started
 * and no database is needed. The {@code prod} start against MySQL is {@code DataSeederProfileIT}.
 */
class DataSeederProfileTest {

    private static final List<Class<?>> SEED_BEANS = List.of(
            DataSeeder.class, DemoUserSeedService.class, TransactionSeedService.class, FraudAlertSeedService.class);

    @ParameterizedTest(name = "profiles={0} -> registered={1}")
    @CsvSource({"dev, true", "prod, false", "test, false", "default, false", "'prod,dev', true"})
    @DisplayName("Seed beans exist in dev only")
    void seedBeans_devOnly(String profiles, boolean expected) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles.split(","));
            SEED_BEANS.forEach(context::register);

            for (var bean : SEED_BEANS) {
                var registered = context.getBeanFactory().getBeanNamesForType(bean, true, false).length > 0;
                assertThat(registered).as(bean.getSimpleName() + " under " + profiles).isEqualTo(expected);
            }
        }
    }
}
