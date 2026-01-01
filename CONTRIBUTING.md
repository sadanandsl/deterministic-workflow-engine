# Contributing to Workflow Orchestrator

Thank you for your interest in contributing to the Workflow Orchestrator! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Building the Project](#building-the-project)
- [Running Tests](#running-tests)
- [Running Examples](#running-examples)
- [Project Structure](#project-structure)
- [How to Add a New Workflow](#how-to-add-a-new-workflow)
- [How to Add a New Task Type](#how-to-add-a-new-task-type)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)

---

## Code of Conduct

This project adheres to a code of conduct. By participating, you are expected to uphold this code. Please be respectful and constructive in all interactions.

---

## Getting Started

### Prerequisites

- **Java 17+** - [Download OpenJDK](https://adoptium.net/)
- **Maven 3.9+** - [Download Maven](https://maven.apache.org/download.cgi)
- **Docker** - [Download Docker](https://www.docker.com/products/docker-desktop)
- **Git** - [Download Git](https://git-scm.com/)

### Fork and Clone

1. Fork the repository on GitHub
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/workflow-orchestrator.git
   cd workflow-orchestrator
   ```
3. Add the upstream remote:
   ```bash
   git remote add upstream https://github.com/ORIGINAL_OWNER/workflow-orchestrator.git
   ```

---

## Development Setup

### 1. Start Infrastructure

Start PostgreSQL and Kafka using Docker Compose:

```bash
docker-compose up -d postgres kafka
```

Verify services are running:

```bash
docker-compose ps
```

### 2. Configure Your IDE

#### IntelliJ IDEA
1. Open the project (File → Open → select `pom.xml`)
2. Enable annotation processing (Settings → Build → Compiler → Annotation Processors)
3. Import as Maven project

#### VS Code
1. Install the "Extension Pack for Java"
2. Open the folder
3. VS Code will auto-detect the Maven project

### 3. Verify Setup

Run a quick build to ensure everything is configured:

```bash
mvn clean compile
```

---

## Building the Project

### Full Build

Build all modules with tests:

```bash
mvn clean install
```

### Skip Tests

For faster iteration:

```bash
mvn clean install -DskipTests
```

### Build Specific Module

```bash
mvn clean install -pl orchestrator-core -am
```

Options:
- `-pl <module>` - Build specific module
- `-am` - Also build dependencies

### Build Docker Image

```bash
docker build -t workflow-orchestrator:latest .
```

---

## Running Tests

### Run All Tests

```bash
mvn test
```

### Run Tests for a Specific Module

```bash
mvn test -pl orchestrator-core
```

### Run a Specific Test Class

```bash
mvn test -pl orchestrator-core -Dtest=WorkflowStateTest
```

### Run Tests with Coverage

```bash
mvn test jacoco:report
```

Coverage report will be in `target/site/jacoco/index.html`.

---

## Running Examples

### Order Processing Demo (No Infrastructure Required)

The demo runs entirely in-memory, demonstrating core concepts:

```bash
cd orchestrator-examples
mvn exec:java -Dexec.mainClass="com.orchestrator.examples.order.OrderWorkflowDemo"
```

This demonstrates:
1. Normal workflow execution
2. Automatic retry on failure
3. Worker crash recovery (lease expiration)
4. Duplicate execution prevention (idempotency)
5. Saga compensation on failure

### Full Orchestrator with API

1. Ensure infrastructure is running:
   ```bash
   docker-compose up -d postgres kafka
   ```

2. Start the orchestrator API:
   ```bash
   cd orchestrator-api
   mvn spring-boot:run
   ```

3. The API will be available at `http://localhost:8080`

4. Test the health endpoint:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

### Run with Docker Compose

Start everything together:

```bash
docker-compose up --build
```

---

## Project Structure

```
workflow-orchestrator/
├── orchestrator-core/          # Domain models, interfaces, repositories
│   └── src/main/java/
│       └── com/orchestrator/core/
│           ├── model/          # WorkflowInstance, TaskExecution, etc.
│           ├── repository/     # Repository interfaces
│           └── exception/      # Domain exceptions
│
├── orchestrator-engine/        # Execution engine
│   └── src/main/java/
│       └── com/orchestrator/engine/
│           ├── coordinator/    # WorkflowCoordinator, TaskCoordinator
│           ├── persistence/    # Repository implementations
│           └── service/        # Service interfaces
│
├── orchestrator-api/           # REST API (Spring Boot)
│   └── src/main/java/
│       └── com/orchestrator/api/
│           ├── controller/     # REST controllers
│           └── config/         # Spring configuration
│
├── orchestrator-worker/        # Worker SDK
│   └── src/main/java/
│       └── com/orchestrator/worker/
│           ├── WorkerClient.java
│           └── ActivityContext.java
│
├── orchestrator-scheduler/     # Time-based scheduling
├── orchestrator-recovery/      # Failure detection & recovery
├── orchestrator-advisory/      # LLM advisory (read-only)
├── orchestrator-examples/      # Example workflows
│
├── docker-compose.yml          # Local development infrastructure
├── Dockerfile                  # Production container build
└── pom.xml                     # Parent POM
```

---

## How to Add a New Workflow

### Step 1: Create a Workflow Definition

Create a new class in `orchestrator-examples` or your own module:

```java
package com.orchestrator.examples.myworkflow;

import com.orchestrator.core.model.*;
import java.util.*;

public class MyWorkflowDefinition {
    
    public static final String NAMESPACE = "my-namespace";
    public static final String WORKFLOW_NAME = "my-workflow";
    
    public static WorkflowDefinition create() {
        List<TaskDefinition> tasks = new ArrayList<>();
        
        // Define tasks
        tasks.add(TaskDefinition.builder()
            .taskId("step-1")
            .type(TaskType.ACTIVITY)
            .activityType("myworkflow.step1")
            .retryPolicy(RetryPolicy.defaultPolicy())
            .timeout(Duration.ofMinutes(5))
            .build());
        
        tasks.add(TaskDefinition.builder()
            .taskId("step-2")
            .type(TaskType.ACTIVITY)
            .activityType("myworkflow.step2")
            .compensationTaskId("undo-step-2")  // Optional: for saga
            .build());
        
        // Define edges (execution order)
        Map<String, List<String>> edges = Map.of(
            "step-1", List.of("step-2")
        );
        
        return WorkflowDefinition.builder()
            .namespace(NAMESPACE)
            .name(WORKFLOW_NAME)
            .tasks(tasks)
            .edges(edges)
            .entryTaskId("step-1")
            .terminalTaskIds(Set.of("step-2"))
            .defaultRetryPolicy(RetryPolicy.defaultPolicy())
            .maxDuration(Duration.ofHours(1))
            .build();
    }
}
```

### Step 2: Implement Activity Handlers

```java
package com.orchestrator.examples.myworkflow;

public class MyActivityHandlers {
    
    public static JsonNode step1(String idempotencyKey, JsonNode input) {
        // Implement your business logic
        // Use idempotencyKey for external API calls
        
        return createResult(/* ... */);
    }
    
    public static JsonNode step2(String idempotencyKey, JsonNode input) {
        // Second step logic
        return createResult(/* ... */);
    }
}
```

### Step 3: Register with Workers

```java
WorkerClient worker = new WorkerClient("http://localhost:8080");

worker.registerActivity("myworkflow.step1", context -> {
    return MyActivityHandlers.step1(
        context.getIdempotencyKey(),
        context.getInput()
    );
});

worker.registerActivity("myworkflow.step2", context -> {
    return MyActivityHandlers.step2(
        context.getIdempotencyKey(),
        context.getInput()
    );
});

worker.start();
```

### Step 4: Start the Workflow

Via API:
```bash
curl -X POST http://localhost:8080/api/v1/workflows/my-namespace/my-workflow/start \
  -H "Content-Type: application/json" \
  -d '{
    "runId": "unique-run-id",
    "input": {"key": "value"}
  }'
```

Or programmatically:
```java
workflowService.startWorkflow(StartWorkflowRequest.builder()
    .namespace("my-namespace")
    .workflowName("my-workflow")
    .runId("unique-run-id")
    .input(inputJson)
    .build());
```

---

## How to Add a New Task Type

Task types extend the `TaskType` enum in `orchestrator-core`:

### Step 1: Add the Type

```java
// In orchestrator-core/src/main/java/com/orchestrator/core/model/TaskType.java
public enum TaskType {
    ACTIVITY,           // External activity execution
    WAIT,               // Wait for signal
    TIMER,              // Time-based delay
    SIGNAL,             // Send/receive signal
    CHILD_WORKFLOW,     // Start child workflow
    DECISION,           // Conditional branching
    PARALLEL,           // Parallel execution
    FOREACH,            // Iterate over collection
    COMPENSATION,       // Saga compensation
    HUMAN_TASK,         // Human approval
    MY_NEW_TYPE         // <-- Add your new type
}
```

### Step 2: Implement the Handler

In `orchestrator-engine`, create a handler:

```java
package com.orchestrator.engine.handler;

public class MyNewTypeHandler implements TaskTypeHandler {
    
    @Override
    public TaskType getType() {
        return TaskType.MY_NEW_TYPE;
    }
    
    @Override
    public void execute(TaskExecution execution, TaskDefinition definition) {
        // Implement execution logic
    }
}
```

### Step 3: Register the Handler

In the coordinator configuration, register your handler.

---

## Pull Request Process

### Before Submitting

1. **Create an issue first** - Discuss the change before implementing
2. **Branch from main** - Create a feature branch
   ```bash
   git checkout -b feature/my-feature
   ```
3. **Write tests** - All new code should have tests
4. **Run the full build** - Ensure all tests pass
   ```bash
   mvn clean install
   ```
5. **Update documentation** - Update READMEs if needed

### PR Checklist

- [ ] Code compiles without warnings
- [ ] All tests pass
- [ ] New code has test coverage
- [ ] Documentation updated (if applicable)
- [ ] Commit messages are clear and descriptive
- [ ] No unrelated changes included

### PR Title Format

Use conventional commit format:
- `feat: Add new task type for webhooks`
- `fix: Handle null input in TaskCoordinator`
- `docs: Update CONTRIBUTING guide`
- `test: Add tests for lease expiration`
- `refactor: Extract common lease logic`

### Review Process

1. Submit PR with clear description
2. Address reviewer feedback
3. Squash commits before merge (if requested)
4. PR will be merged once approved

---

## Coding Standards

### Java Style

- Use Java 17+ features (records, switch expressions, etc.)
- Follow standard Java naming conventions
- Maximum line length: 120 characters
- Use meaningful variable and method names

### Logging

```java
// Use SLF4J
private static final Logger log = LoggerFactory.getLogger(MyClass.class);

// Log levels:
log.trace("Detailed trace info");
log.debug("Debugging info");
log.info("Normal operation info");
log.warn("Warning, but continuing");
log.error("Error occurred", exception);
```

### Error Handling

```java
// Use domain-specific exceptions
throw new NotFoundException("WorkflowInstance", instanceId.toString());
throw new InvalidStateTransitionException(currentState, targetState);

// Don't catch generic Exception unless necessary
try {
    // ...
} catch (SpecificException e) {
    // Handle specifically
}
```

### Immutability

Prefer immutable objects using Java records:

```java
public record TaskExecution(
    UUID executionId,
    UUID workflowInstanceId,
    String taskId,
    TaskState state
) {
    // Methods that "modify" return new instances
    public TaskExecution withCompleted(JsonNode output) {
        return new TaskExecution(/* ... */);
    }
}
```

---

## Questions?

If you have questions about contributing, please:

1. Check existing issues and discussions
2. Open a new issue with your question
3. Tag it with the `question` label

Thank you for contributing! 🎉
