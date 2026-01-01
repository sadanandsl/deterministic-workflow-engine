package com.orchestrator.examples.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestrator.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Order Processing Workflow Example.
 * 
 * Demonstrates:
 * 1. External API calls (inventory check, payment processing)
 * 2. Failure handling with automatic retries
 * 3. Compensation (refund on failure)
 * 4. Human approval for high-value orders
 * 5. Exactly-once execution semantics
 * 
 * Workflow Steps:
 * 1. validate_order - Validate order data
 * 2. check_inventory - Check product availability (external API)
 * 3. human_approval - Required for orders > $1000 (human task)
 * 4. reserve_inventory - Reserve products
 * 5. process_payment - Charge customer (external API)
 * 6. ship_order - Create shipping label and ship
 * 7. send_confirmation - Send order confirmation email
 * 
 * Compensation Chain:
 * - If payment fails after reservation -> release_inventory
 * - If shipping fails after payment -> refund_payment, release_inventory
 */
public class OrderProcessingWorkflow {
    
    private static final Logger log = LoggerFactory.getLogger(OrderProcessingWorkflow.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static final String NAMESPACE = "ecommerce";
    public static final String WORKFLOW_NAME = "order-processing";
    public static final int VERSION = 1;
    
    // Task IDs
    public static final String TASK_VALIDATE_ORDER = "validate_order";
    public static final String TASK_CHECK_INVENTORY = "check_inventory";
    public static final String TASK_HUMAN_APPROVAL = "human_approval";
    public static final String TASK_RESERVE_INVENTORY = "reserve_inventory";
    public static final String TASK_PROCESS_PAYMENT = "process_payment";
    public static final String TASK_SHIP_ORDER = "ship_order";
    public static final String TASK_SEND_CONFIRMATION = "send_confirmation";
    
    // Compensation Task IDs
    public static final String TASK_RELEASE_INVENTORY = "release_inventory";
    public static final String TASK_REFUND_PAYMENT = "refund_payment";
    
    /**
     * Creates the workflow definition for order processing.
     */
    public static WorkflowDefinition createDefinition() {
        List<TaskDefinition> tasks = new ArrayList<>();
        
        // Task 1: Validate Order
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_VALIDATE_ORDER)
            .type(TaskType.ACTIVITY)
            .activityType("order.validate")
            .displayName("Validate Order")
            .description("Validates order data, customer info, and product availability")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(5))
                .backoffMultiplier(2.0)
                .retryableErrors(Set.of("ValidationException"))
                .nonRetryableErrors(Set.of("InvalidOrderException"))
                .build())
            .timeout(Duration.ofSeconds(30))
            .build());
        
        // Task 2: Check Inventory (External API)
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_CHECK_INVENTORY)
            .type(TaskType.ACTIVITY)
            .activityType("inventory.check")
            .displayName("Check Inventory")
            .description("Calls external inventory service to verify product availability")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(5)
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(60))
                .backoffMultiplier(2.0)
                .retryableErrors(Set.of("InventoryServiceUnavailable", "TimeoutException"))
                .build())
            .timeout(Duration.ofMinutes(2))
            .build());
        
        // Task 3: Human Approval (for high-value orders)
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_HUMAN_APPROVAL)
            .type(TaskType.HUMAN)
            .activityType("order.approval")
            .displayName("Manager Approval")
            .description("Requires manual approval for orders over $1000")
            .conditionExpression("${input.requiresApproval == true}")
            .timeout(Duration.ofHours(24))
            .build());
        
        // Task 4: Reserve Inventory
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_RESERVE_INVENTORY)
            .type(TaskType.ACTIVITY)
            .activityType("inventory.reserve")
            .displayName("Reserve Inventory")
            .description("Reserves products in warehouse")
            .compensationTaskId(TASK_RELEASE_INVENTORY)
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .backoffMultiplier(2.0)
                .build())
            .timeout(Duration.ofSeconds(30))
            .build());
        
        // Task 5: Process Payment (External API)
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_PROCESS_PAYMENT)
            .type(TaskType.ACTIVITY)
            .activityType("payment.process")
            .displayName("Process Payment")
            .description("Charges customer via payment gateway")
            .compensationTaskId(TASK_REFUND_PAYMENT)
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofSeconds(30))
                .backoffMultiplier(2.0)
                .retryableErrors(Set.of("PaymentGatewayUnavailable"))
                .nonRetryableErrors(Set.of("InsufficientFundsException", "CardDeclinedException"))
                .build())
            .timeout(Duration.ofMinutes(5))
            .build());
        
        // Task 6: Ship Order
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_SHIP_ORDER)
            .type(TaskType.ACTIVITY)
            .activityType("shipping.create")
            .displayName("Ship Order")
            .description("Creates shipping label and dispatches order")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(10))
                .maxBackoff(Duration.ofMinutes(5))
                .backoffMultiplier(2.0)
                .build())
            .timeout(Duration.ofMinutes(10))
            .build());
        
        // Task 7: Send Confirmation
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_SEND_CONFIRMATION)
            .type(TaskType.ACTIVITY)
            .activityType("notification.email")
            .displayName("Send Confirmation")
            .description("Sends order confirmation email to customer")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(5)
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofMinutes(30))
                .backoffMultiplier(3.0)
                .build())
            .timeout(Duration.ofMinutes(5))
            .build());
        
        // Compensation Tasks
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_RELEASE_INVENTORY)
            .type(TaskType.ACTIVITY)
            .activityType("inventory.release")
            .displayName("Release Inventory")
            .description("Compensation: Releases reserved inventory")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(10)
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofMinutes(10))
                .backoffMultiplier(2.0)
                .build())
            .timeout(Duration.ofMinutes(5))
            .build());
        
        tasks.add(TaskDefinition.builder()
            .taskId(TASK_REFUND_PAYMENT)
            .type(TaskType.ACTIVITY)
            .activityType("payment.refund")
            .displayName("Refund Payment")
            .description("Compensation: Refunds customer payment")
            .retryPolicy(RetryPolicy.builder()
                .maxAttempts(10)
                .initialBackoff(Duration.ofSeconds(10))
                .maxBackoff(Duration.ofMinutes(30))
                .backoffMultiplier(2.0)
                .build())
            .timeout(Duration.ofMinutes(10))
            .build());
        
        // Define workflow graph edges
        Map<String, List<String>> edges = new HashMap<>();
        edges.put(TASK_VALIDATE_ORDER, List.of(TASK_CHECK_INVENTORY));
        edges.put(TASK_CHECK_INVENTORY, List.of(TASK_HUMAN_APPROVAL, TASK_RESERVE_INVENTORY)); // Conditional
        edges.put(TASK_HUMAN_APPROVAL, List.of(TASK_RESERVE_INVENTORY));
        edges.put(TASK_RESERVE_INVENTORY, List.of(TASK_PROCESS_PAYMENT));
        edges.put(TASK_PROCESS_PAYMENT, List.of(TASK_SHIP_ORDER));
        edges.put(TASK_SHIP_ORDER, List.of(TASK_SEND_CONFIRMATION));
        
        return new WorkflowDefinition(
            NAMESPACE,
            WORKFLOW_NAME,
            VERSION,
            tasks,
            edges,
            TASK_VALIDATE_ORDER,
            Set.of(TASK_SEND_CONFIRMATION),
            RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(5))
                .maxBackoff(Duration.ofMinutes(1))
                .backoffMultiplier(2.0)
                .build(),
            Duration.ofHours(48), // Max workflow duration
            CompensationStrategy.BACKWARD,
            Instant.now(),
            "system",
            "Order processing workflow with payment, inventory, and shipping",
            Map.of("domain", "ecommerce", "criticality", "high")
        );
    }
    
    /**
     * Creates sample order input data.
     */
    public static JsonNode createSampleOrderInput(String orderId, BigDecimal totalAmount) {
        ObjectNode input = mapper.createObjectNode();
        input.put("orderId", orderId);
        input.put("customerId", "CUST-" + UUID.randomUUID().toString().substring(0, 8));
        input.put("customerEmail", "customer@example.com");
        input.put("totalAmount", totalAmount.toString());
        input.put("currency", "USD");
        input.put("requiresApproval", totalAmount.compareTo(new BigDecimal("1000")) > 0);
        
        ObjectNode shippingAddress = mapper.createObjectNode();
        shippingAddress.put("street", "123 Main St");
        shippingAddress.put("city", "San Francisco");
        shippingAddress.put("state", "CA");
        shippingAddress.put("zip", "94102");
        shippingAddress.put("country", "US");
        input.set("shippingAddress", shippingAddress);
        
        var items = mapper.createArrayNode();
        ObjectNode item1 = mapper.createObjectNode();
        item1.put("productId", "PROD-001");
        item1.put("productName", "Widget Pro");
        item1.put("quantity", 2);
        item1.put("unitPrice", "99.99");
        items.add(item1);
        
        ObjectNode item2 = mapper.createObjectNode();
        item2.put("productId", "PROD-002");
        item2.put("productName", "Gadget Plus");
        item2.put("quantity", 1);
        item2.put("unitPrice", "149.99");
        items.add(item2);
        
        input.set("items", items);
        
        return input;
    }
}
