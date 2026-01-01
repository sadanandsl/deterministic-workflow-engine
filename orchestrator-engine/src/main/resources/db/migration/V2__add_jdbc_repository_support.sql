-- V2: Add missing columns and adjust schema for JDBC repository implementations

-- Update workflow_definitions table structure to match JdbcWorkflowDefinitionRepository
ALTER TABLE workflow_definitions 
    ADD COLUMN IF NOT EXISTS tasks JSONB,
    ADD COLUMN IF NOT EXISTS edges JSONB,
    ADD COLUMN IF NOT EXISTS entry_task_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS terminal_task_ids JSONB,
    ADD COLUMN IF NOT EXISTS default_retry_max_attempts INTEGER DEFAULT 3,
    ADD COLUMN IF NOT EXISTS default_retry_initial_delay_ms BIGINT DEFAULT 1000,
    ADD COLUMN IF NOT EXISTS default_retry_max_delay_ms BIGINT DEFAULT 60000,
    ADD COLUMN IF NOT EXISTS default_retry_backoff_multiplier DOUBLE PRECISION DEFAULT 2.0,
    ADD COLUMN IF NOT EXISTS max_duration_ms BIGINT DEFAULT 86400000,
    ADD COLUMN IF NOT EXISTS compensation_strategy VARCHAR(50) DEFAULT 'BACKWARD',
    ADD COLUMN IF NOT EXISTS labels JSONB DEFAULT '{}';

-- Migrate existing data if tasks_json exists
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns 
               WHERE table_name = 'workflow_definitions' AND column_name = 'tasks_json') THEN
        UPDATE workflow_definitions SET tasks = tasks_json WHERE tasks IS NULL;
    END IF;
END $$;

-- Change version to integer type if it's varchar
-- This is handled carefully to preserve data
ALTER TABLE workflow_definitions 
    ALTER COLUMN version TYPE INTEGER USING version::integer;

-- Update events table to match JdbcEventRepository expectations
ALTER TABLE events
    ADD COLUMN IF NOT EXISTS task_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS task_execution_id UUID,
    ADD COLUMN IF NOT EXISTS payload JSONB,
    ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS event_timestamp TIMESTAMP WITH TIME ZONE;

-- Migrate timestamp to event_timestamp if needed
UPDATE events SET event_timestamp = timestamp WHERE event_timestamp IS NULL;

-- Create index for replay queries
CREATE INDEX IF NOT EXISTS idx_events_workflow_sequence 
    ON events(workflow_instance_id, sequence_number ASC);

-- Update task_executions for better querying
CREATE INDEX IF NOT EXISTS idx_task_exec_idempotency_key 
    ON task_executions(idempotency_key);

-- Add workflow execution history support
CREATE TABLE IF NOT EXISTS workflow_execution_history (
    history_id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances(instance_id),
    
    -- Snapshot data
    state_snapshot JSONB NOT NULL,
    event_sequence_number BIGINT NOT NULL,
    
    -- Timing
    snapshot_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Indexing for efficient replay
    CONSTRAINT uk_history_snapshot UNIQUE (workflow_instance_id, event_sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_history_workflow ON workflow_execution_history(workflow_instance_id);
CREATE INDEX IF NOT EXISTS idx_history_sequence ON workflow_execution_history(workflow_instance_id, event_sequence_number DESC);

-- Prometheus metrics collection support
CREATE TABLE IF NOT EXISTS metrics_snapshots (
    snapshot_id UUID PRIMARY KEY,
    snapshot_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Counts
    workflows_pending INTEGER NOT NULL DEFAULT 0,
    workflows_running INTEGER NOT NULL DEFAULT 0,
    workflows_completed INTEGER NOT NULL DEFAULT 0,
    workflows_failed INTEGER NOT NULL DEFAULT 0,
    
    tasks_pending INTEGER NOT NULL DEFAULT 0,
    tasks_running INTEGER NOT NULL DEFAULT 0,
    tasks_completed INTEGER NOT NULL DEFAULT 0,
    tasks_failed INTEGER NOT NULL DEFAULT 0,
    
    -- Latencies (in milliseconds)
    avg_task_latency_ms DOUBLE PRECISION,
    p50_task_latency_ms DOUBLE PRECISION,
    p95_task_latency_ms DOUBLE PRECISION,
    p99_task_latency_ms DOUBLE PRECISION,
    
    -- Retries
    total_retry_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_metrics_time ON metrics_snapshots(snapshot_at DESC);
