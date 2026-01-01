package com.orchestrator.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main application entry point for the Workflow Orchestrator.
 */
@SpringBootApplication
@ComponentScan(basePackages = {
    "com.orchestrator.api",
    "com.orchestrator.engine",
    "com.orchestrator.recovery"
})
public class OrchestratorApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
