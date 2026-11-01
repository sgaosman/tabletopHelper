package com.tabletophelper.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Runs one maintenance job after reference data seeding, then exits. Does nothing unless
 * {@code --maintenance.job} is set. Dry run by default; {@code --maintenance.apply=true} writes.
 * <p>
 * Local example (no web server):
 * {@code ./gradlew bootRun --args="--maintenance.job=recompute-spell-slots --spring.main.web-application-type=none"}
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class MaintenanceRunner implements ApplicationRunner {

    private final List<MaintenanceJob> jobs;
    private final ApplicationContext context;
    private final PlatformTransactionManager transactionManager;

    @Value("${maintenance.job:}")
    private String jobName;

    @Value("${maintenance.apply:false}")
    private boolean apply;

    @Override
    public void run(ApplicationArguments args) {
        if (jobName == null || jobName.isBlank()) return;

        MaintenanceJob job = jobs.stream()
                .filter(j -> j.name().equals(jobName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown maintenance job '" + jobName
                        + "'. Available: " + jobs.stream().map(MaintenanceJob::name).toList()));

        MaintenanceReport report = new TransactionTemplate(transactionManager).execute(status -> job.run(apply));
        log.info(report.render());

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
