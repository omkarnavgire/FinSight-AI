package com.finsight.budgeting.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the {@link Clock} used to resolve "the current period" (D-6, D-7).
 *
 * <p>Uses the server's default time zone. Tests can replace it with a fixed clock.
 */
@Configuration
public class BudgetingClockConfig {

    /** System clock in the server's default time zone, unless another {@link Clock} bean exists. */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
