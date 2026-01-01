-- Workflow Orchestrator Schema
-- V1: Initial schema with exactly-once semantics support

-- Custom Types
CREATE TYPE workflow_state AS ENUM (
    'PENDING', 'RUNNING', 'PAUSED', 'COMPENSATING', 
    'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
);

CREATE TYPE task_state AS ENUM (
    'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED', 
    'CANCELLED', 'TIMED_OUT', 'WAITING', 'SKIPPED'
);

CREATE TYPE task_type AS ENUM (
    'ACTIVITY', 'WAIT', 'TIMER', 'SIGNAL', 'CHILD_WORKFLOW',
    'DECISION', 'PARALLEL', 'FOREACH', 'COMPENSATION', 'HUMAN_TASK'
);

CREATE TYPE event_type AS ENUM (
    'WORKFLOW_STARTED', 'WORKFLOW_COMPLETED', 'WORKFLOW_FAILED',
    'WORKFLOW_CANCELLED', 'WORKFLOW_PAUSED', 'WORKFLOW_RESUMED',
    'WORKFLOW_TIMED_OUT', 'TASK_SCHEDULED', 'TASK_STARTED',
    'TASK_COMPLETED', 'TASK_FAILED', 'TASK_RETRYING',
    'TASK_TIMED_OUT', 'TASK_CANCELLED', 'SIGNAL_RECEIVED',
    'TIMER_FIRED', 'COMPENSATION_STARTED', 'COMPENSATION_COMPLETED',
    'CHILD_WORKFLOW_STARTED', 'CHILD_WORKFLOW_COMPLETED',
    'HUMAN_TASK_ASSIGNED', 'HUMAN_TASK_COMPLETED'
);

-- Workflow Definitions (immutable)
CREATE TABLE workflow_definitions (
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    description TEXT,
    
    -- Graph structure (stored as JSONB)
    tasks_json JSONB NOT NULL,
    
    -- Policies
    default_retry_policy_json JSONB,
    timeout_seconds BIGINT,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    
    PRIMARY KEY (namespace, name, version)
);

CREATE INDEX idx_workflow_def_namespace ON workflow_definitions(namespace);
CREATE INDEX idx_workflow_def_name ON workflow_definitions(namespace, name);

-- Workflow Instances
CREATE TABLE workflow_instances (
    instance_id UUID PRIMARY KEY,
    
    -- Definition reference
    namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_version INTEGER NOT NULL,
    
    -- Execution identity
    run_id VARCHAR(512) NOT NULL,
    correlation_id VARCHAR(255),
    
    -- State
    state VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    current_task_id VARCHAR(255),
    completed_task_ids JSONB NOT NULL DEFAULT '[]',
    failed_task_ids JSONB NOT NULL DEFAULT '[]',
    
    -- Data
    input_json JSONB,
    output_json JSONB,
    task_outputs_json JSONB NOT NULL DEFAULT '{}',
    
    -- Error tracking
    last_error TEXT,
    last_error_task_id VARCHAR(255),
    
    -- Timing
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    deadline TIMESTAMP WITH TIME ZONE,
    
    -- Recovery
    recovery_attempts INTEGER NOT NULL DEFAULT 0,
    last_recovery_at TIMESTAMP WITH TIME ZONE,
    
    -- Optimistic locking
    sequence_number BIGINT NOT NULL DEFAULT 0,
    
    -- Idempotency constraint
    CONSTRAINT uk_workflow_instance_idempotency UNIQUE (namespace, workflow_name, run_id)
);

CREATE INDEX idx_workflow_inst_state ON workflow_instances(state);
CREATE INDEX idx_workflow_inst_correlation ON workflow_instances(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_workflow_inst_deadline ON workflow_instances(deadline) WHERE deadline IS NOT NULL AND completed_at IS NULL;
CREATE INDEX idx_workflow_inst_namespace ON workflow_instances(namespace, workflow_name);

-- Task Executions
CREATE TABLE task_executions (
    execution_id UUID PRIMARY KEY,
    
    -- Foreign keys
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances(instance_id),
    task_id VARCHAR(255) NOT NULL,
    
    -- Idempotency
    idempotency_key VARCHAR(512) NOT NULL UNIQUE,
    attempt_number INTEGER NOT NULL DEFAULT 1,
    
    -- State
    state VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    
    -- Lease for exactly-once execution
    lease_holder UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    fence_token BIGINT NOT NULL DEFAULT 0,
    
    -- Data
    input_json JSONB,
    output_json JSONB,
    error_message TEXT,
    error_code VARCHAR(255),
    stack_trace TEXT,
    
    -- Timing
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Tracing
    trace_id VARCHAR(255),
    span_id VARCHAR(255),
    worker_id VARCHAR(255)
);

CREATE INDEX idx_task_exec_workflow ON task_executions(workflow_instance_id);
CREATE INDEX idx_task_exec_state ON task_executions(state);
CREATE INDEX idx_task_exec_lease ON task_executions(lease_expires_at) WHERE state = 'RUNNING';
CREATE INDEX idx_task_exec_task ON task_executions(workflow_instance_id, task_id);
CREATE INDEX idx_task_exec_queued ON task_executions(scheduled_at) WHERE state = 'QUEUED';

-- Events (append-only log)
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    
    -- Foreign key
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances(instance_id),
    
    -- Ordering
    sequence_number BIGINT NOT NULL,
    
    -- Event data
    event_type VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    payload_json JSONB,
    
    -- Causality
    caused_by_event_id UUID,
    idempotency_key VARCHAR(512) NOT NULL,
    
    -- Tracing
    trace_id VARCHAR(255),
    span_id VARCHAR(255),
    
    -- Actor
    actor_type VARCHAR(50),
    actor_id VARCHAR(255),
    
    -- Ensure ordering within workflow
    CONSTRAINT uk_event_sequence UNIQUE (workflow_instance_id, sequence_number),
    CONSTRAINT uk_event_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_event_workflow ON events(workflow_instance_id);
CREATE INDEX idx_event_type ON events(event_type);
CREATE INDEX idx_event_timestamp ON events(timestamp);
CREATE INDEX idx_event_sequence ON events(workflow_instance_id, sequence_number);

-- Execution Leases (distributed locks for exactly-once semantics)
CREATE TABLE execution_leases (
    lease_key VARCHAR(512) PRIMARY KEY,
    
    -- Ownership
    holder_id UUID NOT NULL,
    holder_address VARCHAR(255),
    
    -- Timing
    acquired_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_duration_ms BIGINT NOT NULL,
    renewal_count INTEGER NOT NULL DEFAULT 0,
    
    -- Fencing
    fence_token BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX idx_lease_expires ON execution_leases(expires_at);
CREATE INDEX idx_lease_holder ON execution_leases(holder_id);

-- Scheduled Timers
CREATE TABLE scheduled_timers (
    timer_id UUID PRIMARY KEY,
    
    -- Foreign key
    workflow_instance_id UUID NOT NULL REFERENCES workflow_instances(instance_id),
    task_id VARCHAR(255),
    
    -- Timer config
    timer_type VARCHAR(50) NOT NULL, -- DELAY, CRON, ABSOLUTE
    fire_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    -- State
    fired BOOLEAN NOT NULL DEFAULT FALSE,
    fired_at TIMESTAMP WITH TIME ZONE,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    payload JSONB
);

CREATE INDEX idx_timer_fire ON scheduled_timers(fire_at) WHERE fired = FALSE;
CREATE INDEX idx_timer_workflow ON scheduled_timers(workflow_instance_id);

-- Activity Type Registry (for worker routing)
CREATE TABLE activity_types (
    activity_type VARCHAR(255) PRIMARY KEY,
    description TEXT,
    default_timeout_seconds INTEGER NOT NULL DEFAULT 300,
    default_retry_policy JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Task Queue (for polling workers)
CREATE TABLE task_queue (
    queue_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES task_executions(execution_id),
    activity_type VARCHAR(255) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    enqueued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    visible_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_queue_poll ON task_queue(activity_type, visible_at, priority DESC);
