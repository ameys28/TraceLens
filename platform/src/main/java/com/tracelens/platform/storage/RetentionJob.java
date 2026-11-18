package com.tracelens.platform.storage;

import com.tracelens.platform.config.TraceLensProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Partition lifecycle: pre-creates the next days' partitions and drops expired ones.
 * Dropping a partition is instant and leaves no bloat, unlike DELETE + VACUUM.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final TraceLensProperties props;

    public RetentionJob(JdbcTemplate jdbc, TraceLensProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        run();
    }

    @Scheduled(cron = "0 7 * * * *")
    public void run() {
        try {
            Integer created = jdbc.queryForObject("SELECT tracelens_ensure_partitions(3)", Integer.class);
            Integer dropped = jdbc.queryForObject("SELECT tracelens_drop_partitions_older_than(?)", Integer.class, props.retentionDays());
            int defaults = jdbc.update("DELETE FROM spans_default WHERE start_time < now() - make_interval(days => ?)", props.retentionDays())
                    + jdbc.update("DELETE FROM traces_default WHERE start_time < now() - make_interval(days => ?)", props.retentionDays());
            int rollups = 0;
            for (String table : new String[]{"op_rollup_minute", "op_latency_bucket_minute", "edge_rollup_minute", "sampling_stats_minute"}) {
                rollups += jdbc.update("DELETE FROM " + table + " WHERE minute < now() - make_interval(days => ?)", props.rollupRetentionDays());
            }
            log.info("retention: created {} partitions, dropped {}, purged {} default-partition rows and {} rollup rows",
                    created, dropped, defaults, rollups);
        } catch (Exception e) {
            log.warn("retention job failed: {}", e.getMessage());
        }
    }
}
