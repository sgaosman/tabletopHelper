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
 * Runs one maintenance job, then exits. Does nothing unless {@code --maintenance.job} is set. Dry run by default;
 * {@code --maintenance.apply=true} writes. A maintenance start loads no content (D169: content.ContentStartup is
 * off for it), so a job sees the reference data as the database holds it; {@code preview-content} runs the loaders
 * itself, in a transaction it rolls back.
 * <p>
 * Local example, on a random free port (the error handler needs the web server's settings, so
 * {@code --spring.main.web-application-type=none} doesn't start):
 * {@code ./gradlew bootRun --args="--maintenance.job=recompute-spell-slots --server.port=0"}
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
