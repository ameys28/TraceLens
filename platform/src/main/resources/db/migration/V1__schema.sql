-- TraceLens schema
--  * spans / traces: sampled raw data, range-partitioned by day (retention = DROP PARTITION)
--  * *_minute tables: pre-sampling per-minute rollups (accurate RED metrics + dependency graph)
--  * alerts: anomaly detector output

-- ------------------------------------------------------------------ raw, sampled data
CREATE TABLE spans (
    trace_id        CHAR(32)     NOT NULL,
    span_id         CHAR(16)     NOT NULL,
    parent_span_id  VARCHAR(16)  NOT NULL DEFAULT '',
    service_name    TEXT         NOT NULL,
    operation       TEXT         NOT NULL,
    kind            VARCHAR(12)  NOT NULL,
    start_time      TIMESTAMPTZ  NOT NULL,
    start_us        BIGINT       NOT NULL,
    duration_us     BIGINT       NOT NULL,
    status_code     VARCHAR(5)   NOT NULL,
    status_message  TEXT         NOT NULL DEFAULT '',
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    events          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (trace_id, span_id, start_time)
) PARTITION BY RANGE (start_time);

CREATE INDEX idx_spans_trace ON spans (trace_id);
CREATE INDEX idx_spans_service_errors ON spans (service_name, start_time DESC) WHERE status_code = 'ERROR';

CREATE TABLE traces (
    trace_id        CHAR(32)     NOT NULL,
    start_time      TIMESTAMPTZ  NOT NULL,
    start_us        BIGINT       NOT NULL,
    duration_us     BIGINT       NOT NULL,
    root_service    TEXT         NOT NULL,
    root_operation  TEXT         NOT NULL,
    span_count      INT          NOT NULL,
    error_count     INT          NOT NULL,
    services        TEXT[]       NOT NULL,
    sampling_reason VARCHAR(16)  NOT NULL,
    PRIMARY KEY (trace_id, start_time)
) PARTITION BY RANGE (start_time);

CREATE INDEX idx_traces_start ON traces (start_time DESC);
CREATE INDEX idx_traces_root ON traces (root_service, start_time DESC);
CREATE INDEX idx_traces_services ON traces USING GIN (services);
CREATE INDEX idx_traces_trace_id ON traces (trace_id);

-- Safety net for rows outside the pre-created daily partitions (e.g. badly skewed clocks).
CREATE TABLE spans_default PARTITION OF spans DEFAULT;
CREATE TABLE traces_default PARTITION OF traces DEFAULT;

-- Creates daily partitions from yesterday to today + days_ahead (UTC). Idempotent.
CREATE OR REPLACE FUNCTION tracelens_ensure_partitions(days_ahead INT) RETURNS INT AS $$
DECLARE
    d       DATE;
    tbl     TEXT;
    part    TEXT;
    created INT := 0;
BEGIN
    FOR d IN
        SELECT g::date
        FROM generate_series((now() AT TIME ZONE 'UTC')::date - 1,
                             (now() AT TIME ZONE 'UTC')::date + days_ahead,
                             INTERVAL '1 day') AS g
    LOOP
        FOREACH tbl IN ARRAY ARRAY['spans', 'traces'] LOOP
            part := tbl || '_p' || to_char(d, 'YYYYMMDD');
            IF to_regclass(part) IS NULL THEN
                BEGIN
                    EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                                   part, tbl,
                                   (d::timestamp AT TIME ZONE 'UTC'),
                                   ((d + 1)::timestamp AT TIME ZONE 'UTC'));
                    created := created + 1;
                EXCEPTION WHEN others THEN
                    RAISE WARNING 'could not create partition %: %', part, SQLERRM;
                END;
            END IF;
        END LOOP;
    END LOOP;
    RETURN created;
END;
$$ LANGUAGE plpgsql;

-- Drops daily partitions older than keep_days. Returns how many were dropped.
CREATE OR REPLACE FUNCTION tracelens_drop_partitions_older_than(keep_days INT) RETURNS INT AS $$
DECLARE
    r       RECORD;
    cutoff  DATE := (now() AT TIME ZONE 'UTC')::date - keep_days;
    dropped INT := 0;
BEGIN
    FOR r IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname IN ('spans', 'traces')
          AND c.relname ~ '_p[0-9]{8}$'
    LOOP
        IF to_date(right(r.relname, 8), 'YYYYMMDD') < cutoff THEN
            EXECUTE format('DROP TABLE %I', r.relname);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$ LANGUAGE plpgsql;

SELECT tracelens_ensure_partitions(3);

-- ------------------------------------------------------------------ rollups (all traffic)
CREATE TABLE op_rollup_minute (
    minute            TIMESTAMPTZ NOT NULL,
    service           TEXT        NOT NULL,
    operation         TEXT        NOT NULL,
    span_count        BIGINT      NOT NULL,
    error_count       BIGINT      NOT NULL,
    total_duration_us BIGINT      NOT NULL,
    max_duration_us   BIGINT      NOT NULL,
    PRIMARY KEY (minute, service, operation)
);
CREATE INDEX idx_op_rollup_service ON op_rollup_minute (service, minute);

CREATE TABLE op_latency_bucket_minute (
    minute    TIMESTAMPTZ NOT NULL,
    service   TEXT        NOT NULL,
    operation TEXT        NOT NULL,
    bucket    SMALLINT    NOT NULL,
    count     BIGINT      NOT NULL,
    PRIMARY KEY (minute, service, operation, bucket)
);
CREATE INDEX idx_op_bucket_service ON op_latency_bucket_minute (service, minute);

CREATE TABLE edge_rollup_minute (
    minute            TIMESTAMPTZ NOT NULL,
    caller            TEXT        NOT NULL,
    callee            TEXT        NOT NULL,
    call_count        BIGINT      NOT NULL,
    error_count       BIGINT      NOT NULL,
    total_duration_us BIGINT      NOT NULL,
    PRIMARY KEY (minute, caller, callee)
);

CREATE TABLE sampling_stats_minute (
    minute      TIMESTAMPTZ NOT NULL,
    reason      VARCHAR(16) NOT NULL,
    trace_count BIGINT      NOT NULL,
    span_count  BIGINT      NOT NULL,
    PRIMARY KEY (minute, reason)
);

-- ------------------------------------------------------------------ alerts
CREATE TABLE alerts (
    id               BIGSERIAL PRIMARY KEY,
    alert_key        TEXT             NOT NULL,
    type             VARCHAR(16)      NOT NULL,
    severity         VARCHAR(16)      NOT NULL,
    service          TEXT             NOT NULL,
    operation        TEXT             NOT NULL,
    message          TEXT             NOT NULL,
    observed         DOUBLE PRECISION NOT NULL,
    baseline         DOUBLE PRECISION NOT NULL,
    example_trace_id CHAR(32),
    top_error        TEXT,
    status           VARCHAR(10)      NOT NULL,
    opened_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_alerts_open_key ON alerts (alert_key) WHERE status = 'OPEN';
CREATE INDEX idx_alerts_opened ON alerts (opened_at DESC);
