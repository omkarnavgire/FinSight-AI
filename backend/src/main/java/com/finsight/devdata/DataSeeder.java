package com.finsight.devdata;

import com.finsight.accounts.domain.AccountType;
import com.finsight.accounts.dto.CreateAccountRequest;
import com.finsight.accounts.service.AccountService;
import com.finsight.auth.service.DemoUserSeedService;
import com.finsight.auth.service.UserDirectory;
import com.finsight.budgeting.dto.CreateBudgetRequest;
import com.finsight.budgeting.service.BudgetService;
import com.finsight.cashflow.service.CashFlowService;
import com.finsight.fraud.domain.FraudRuleName;
import com.finsight.fraud.service.FraudAlertSeedService;
import com.finsight.transactions.domain.Category;
import com.finsight.transactions.service.TransactionSeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Seeds synthetic demo data on dev startup when the database has no users (T-61, FR-02-4, AC-SEED-1).
 *
 * <h2>Guards</h2>
 * {@code @Profile("dev")}: the bean does not exist in any other profile. It also refuses to run if
 * {@code prod} is active, if {@code FINSIGHT_DEMO_PASSWORD} is blank, or if any user exists
 * (empty-table check, so a second start changes nothing).
 *
 * <h2>Module boundaries (Q-2)</h2>
 * Orchestrates public services only. Accounts, budgets and the forecast use their normal services;
 * the demo user, the bulk transactions (no fraud rules, Q-1) and the single ABNORMAL_AMOUNT alert
 * use small dev-only seed services owned by auth, transactions and fraud.
 *
 * <h2>All or nothing</h2>
 * Everything runs in one transaction under a temporary security context for the demo user; the
 * previous context is restored afterwards. A failure rolls back all seeded rows and is logged; dev
 * startup continues with an empty database.
 */
@Component
@Profile("dev")
public class DataSeeder implements ApplicationRunner {

    /** Demo login name from design.md. The password is never in source: FINSIGHT_DEMO_PASSWORD. */
    static final String DEMO_EMAIL = "demo@finsight.dev";
    static final String DEMO_NAME = "Demo User";

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserDirectory userDirectory;
    private final DemoUserSeedService demoUserSeedService;
    private final AccountService accountService;
    private final TransactionSeedService transactionSeedService;
    private final BudgetService budgetService;
    private final FraudAlertSeedService fraudAlertSeedService;
    private final CashFlowService cashFlowService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Environment environment;
    private final String demoPassword;

    public DataSeeder(UserDirectory userDirectory,
                      DemoUserSeedService demoUserSeedService,
                      AccountService accountService,
                      TransactionSeedService transactionSeedService,
                      BudgetService budgetService,
                      FraudAlertSeedService fraudAlertSeedService,
                      CashFlowService cashFlowService,
                      TransactionTemplate transactionTemplate,
                      Clock clock,
                      Environment environment,
                      @Value("${finsight.seed.demo-password:}") String demoPassword) {
        this.userDirectory = userDirectory;
        this.demoUserSeedService = demoUserSeedService;
        this.accountService = accountService;
        this.transactionSeedService = transactionSeedService;
        this.budgetService = budgetService;
        this.fraudAlertSeedService = fraudAlertSeedService;
        this.cashFlowService = cashFlowService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.environment = environment;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedIfEmpty();
        } catch (RuntimeException e) {
            log.error("Demo data seeding failed and was rolled back ({})", e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Seeds the demo data if no user exists.
     *
     * @return {@code true} if data was seeded, {@code false} if a guard skipped it
     */
    public boolean seedIfEmpty() {
        if (Set.of(environment.getActiveProfiles()).contains("prod")) {
            log.warn("DataSeeder skipped: the prod profile is active");
            return false;
        }
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("DataSeeder skipped: FINSIGHT_DEMO_PASSWORD is not set");
            return false;
        }
        if (!userDirectory.userIdsAfter(0, 1).isEmpty()) {
            log.info("DataSeeder skipped: users already exist");
            return false;
        }
        var previous = SecurityContextHolder.getContext();
        var hadPrevious = previous.getAuthentication() != null;
        try {
            transactionTemplate.executeWithoutResult(status -> seedAll());
        } finally {
            if (hadPrevious) {
                SecurityContextHolder.setContext(previous);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
        log.info("DataSeeder: demo data created");
        return true;
    }

    /** Runs inside the seeding transaction. */
    private void seedAll() {
        var userId = demoUserSeedService.createDemoUser(DEMO_EMAIL, demoPassword, DEMO_NAME);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);

        var checking = accountService.create(new CreateAccountRequest(
                "Checking", AccountType.CHECKING, new BigDecimal("5000.00"), null)).id();
        var savings = accountService.create(new CreateAccountRequest(
                "Savings", AccountType.SAVINGS, new BigDecimal("12000.00"), null)).id();

        var plan = SyntheticDataPlan.build(LocalDate.now(clock), checking, savings);
        var inserted = transactionSeedService.seed(plan.transactions());

        budgetService.create(new CreateBudgetRequest(Category.GROCERIES, new BigDecimal("600.00"), null, null));
        budgetService.create(new CreateBudgetRequest(Category.DINING, new BigDecimal("300.00"), null, null));
        budgetService.create(new CreateBudgetRequest(Category.TRANSPORT, new BigDecimal("200.00"), null, null));

        fraudAlertSeedService.seedAlert(inserted.get(plan.abnormalIndex()).id(), FraudRuleName.ABNORMAL_AMOUNT);

        cashFlowService.recalculateCurrentUser();
    }
}
