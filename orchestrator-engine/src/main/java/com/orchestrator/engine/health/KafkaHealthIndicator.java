package com.orchestrator.engine.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Health indicator for Kafka connectivity.
 * Only active when Kafka is configured.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private final String bootstrapServers;
    private volatile boolean kafkaEnabled = false;

    public KafkaHealthIndicator() {
        this.bootstrapServers = System.getenv("SPRING_KAFKA_BOOTSTRAP_SERVERS");
        this.kafkaEnabled = bootstrapServers != null && !bootstrapServers.isEmpty();
    }

    @Override
    public Health health() {
        if (!kafkaEnabled) {
            return Health.up()
                .withDetail("kafka", "disabled")
                .withDetail("mode", "polling")
                .build();
        }

        Map<String, Object> details = new HashMap<>();
        details.put("bootstrapServers", bootstrapServers);
        
        try {
            // Simple connectivity check
            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrapServers);
            props.put("request.timeout.ms", "5000");
            props.put("default.api.timeout.ms", "5000");
            
            try (var adminClient = org.apache.kafka.clients.admin.AdminClient.create(props)) {
                var nodes = adminClient.describeCluster().nodes().get();
                details.put("brokerCount", nodes.size());
                details.put("status", "connected");
                
                return Health.up()
                    .withDetails(details)
                    .build();
            }
        } catch (Exception e) {
            details.put("status", "disconnected");
            details.put("error", e.getMessage());
            
            // Kafka being down shouldn't fail the whole service
            // if polling mode is available as fallback
            return Health.up()
                .withDetails(details)
                .withDetail("fallback", "polling mode active")
                .build();
        }
    }
}
