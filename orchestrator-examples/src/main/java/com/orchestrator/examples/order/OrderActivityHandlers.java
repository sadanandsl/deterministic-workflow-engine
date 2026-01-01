package com.orchestrator.examples.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated activity handlers for the Order Processing workflow.
 * 
 * These handlers demonstrate:
 * - Idempotent execution (safe to retry)
 * - External API simulation with configurable failures
 * - Compensation logic for rollback scenarios
 * - Exactly-once semantics with idempotency keys
 */
public class OrderActivityHandlers {
    
    private static final Logger log = LoggerFactory.getLogger(OrderActivityHandlers.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Random random = new Random();
    
    // Failure simulation counters for demonstrating retry behavior
    private static final AtomicInteger inventoryCheckAttempts = new AtomicInteger(0);
    private static final AtomicInteger paymentAttempts = new AtomicInteger(0);
    
    // Configuration for failure simulation
    private static boolean simulateInventoryFailure = false;
    private static boolean simulatePaymentFailure = false;
    private static int failuresBeforeSuccess = 2;
    
    /**
     * Validate Order Activity.
     * 
     * IDEMPOTENT: Safe to call multiple times with same input.
     * Always produces the same output for the same input.
     */
    public static JsonNode validateOrder(String idempotencyKey, JsonNode input) {
        log.info("[{}] Validating order: {}", idempotencyKey, input.get("orderId"));
        
        String orderId = input.get("orderId").asText();
        JsonNode items = input.get("items");
        String totalAmount = input.get("totalAmount").asText();
        
        // Validate required fields
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("Invalid order: missing orderId");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Invalid order: no items");
        }
        
        // Build validation result
        ObjectNode result = mapper.createObjectNode();
        result.put("valid", true);
        result.put("orderId", orderId);
        result.put("itemCount", items.size());
        result.put("totalAmount", totalAmount);
        result.put("validatedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        
        log.info("[{}] Order validated successfully: {} items, total {}", 
            idempotencyKey, items.size(), totalAmount);
        
        return result;
    }
    
    /**
     * Check Inventory Activity (simulated external API call).
     * 
     * DEMONSTRATES:
     * - Transient failures that succeed on retry
     * - Exponential backoff benefit
     * - Idempotent read operation
     */
    public static JsonNode checkInventory(String idempotencyKey, JsonNode input) {
        log.info("[{}] Checking inventory for order: {}", idempotencyKey, input.get("orderId"));
        
        // Simulate transient failures
        if (simulateInventoryFailure) {
            int attempts = inventoryCheckAttempts.incrementAndGet();
            if (attempts <= failuresBeforeSuccess) {
                log.warn("[{}] SIMULATED FAILURE: Inventory service unavailable (attempt {})", 
                    idempotencyKey, attempts);
                throw new RuntimeException("InventoryServiceUnavailable: Connection timeout");
            }
        }
        
        // Simulate API call latency
        sleep(100 + random.nextInt(200));
        
        ObjectNode result = mapper.createObjectNode();
        result.put("available", true);
        result.put("orderId", input.get("orderId").asText());
        result.put("checkedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        
        // Add availability per item
        var availability = mapper.createArrayNode();
        JsonNode items = input.get("items");
        if (items != null) {
            for (JsonNode item : items) {
                ObjectNode itemAvail = mapper.createObjectNode();
                itemAvail.put("productId", item.get("productId").asText());
                itemAvail.put("requested", item.get("quantity").asInt());
                itemAvail.put("available", item.get("quantity").asInt() + random.nextInt(10));
                itemAvail.put("warehouse", "WAREHOUSE-" + (random.nextInt(3) + 1));
                availability.add(itemAvail);
            }
        }
        result.set("itemAvailability", availability);
        
        log.info("[{}] Inventory check complete: all items available", idempotencyKey);
        return result;
    }
    
    /**
     * Reserve Inventory Activity.
     * 
     * DEMONSTRATES:
     * - State-changing operation with compensation
     * - Reservation ID for tracking and rollback
     */
    public static JsonNode reserveInventory(String idempotencyKey, JsonNode input) {
        log.info("[{}] Reserving inventory for order: {}", idempotencyKey, input.get("orderId"));
        
        String reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 12);
        
        ObjectNode result = mapper.createObjectNode();
        result.put("reserved", true);
        result.put("orderId", input.get("orderId").asText());
        result.put("reservationId", reservationId);
        result.put("reservedAt", Instant.now().toString());
        result.put("expiresAt", Instant.now().plusSeconds(3600).toString()); // 1 hour hold
        result.put("idempotencyKey", idempotencyKey);
        
        log.info("[{}] Inventory reserved: {}", idempotencyKey, reservationId);
        return result;
    }
    
    /**
     * Release Inventory Activity (COMPENSATION).
     * 
     * Called when workflow fails after inventory reservation.
     * MUST be idempotent - may be called multiple times.
     */
    public static JsonNode releaseInventory(String idempotencyKey, JsonNode input) {
        String reservationId = input.has("reservationId") ? 
            input.get("reservationId").asText() : "UNKNOWN";
        
        log.info("[{}] COMPENSATION: Releasing inventory reservation: {}", 
            idempotencyKey, reservationId);
        
        ObjectNode result = mapper.createObjectNode();
        result.put("released", true);
        result.put("reservationId", reservationId);
        result.put("releasedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        result.put("reason", "Workflow compensation");
        
        log.info("[{}] COMPENSATION: Inventory released for reservation: {}", 
            idempotencyKey, reservationId);
        return result;
    }
    
    /**
     * Process Payment Activity (simulated external payment gateway).
     * 
     * DEMONSTRATES:
     * - Critical external API call
     * - Idempotent payment processing (same idempotency key = same result)
     * - Non-retryable vs retryable failures
     */
    public static JsonNode processPayment(String idempotencyKey, JsonNode input) {
        log.info("[{}] Processing payment for order: {} amount: {}", 
            idempotencyKey, input.get("orderId"), input.get("totalAmount"));
        
        // Simulate transient failures
        if (simulatePaymentFailure) {
            int attempts = paymentAttempts.incrementAndGet();
            if (attempts <= failuresBeforeSuccess) {
                log.warn("[{}] SIMULATED FAILURE: Payment gateway unavailable (attempt {})", 
                    idempotencyKey, attempts);
                throw new RuntimeException("PaymentGatewayUnavailable: Service temporarily down");
            }
        }
        
        // Simulate API call latency
        sleep(200 + random.nextInt(300));
        
        String transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 12);
        
        ObjectNode result = mapper.createObjectNode();
        result.put("success", true);
        result.put("orderId", input.get("orderId").asText());
        result.put("transactionId", transactionId);
        result.put("amount", input.get("totalAmount").asText());
        result.put("currency", input.has("currency") ? input.get("currency").asText() : "USD");
        result.put("processedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        result.put("paymentMethod", "credit_card");
        result.put("last4", "4242");
        
        log.info("[{}] Payment processed successfully: {} for {}", 
            idempotencyKey, transactionId, input.get("totalAmount"));
        return result;
    }
    
    /**
     * Refund Payment Activity (COMPENSATION).
     * 
     * Called when workflow fails after payment processing.
     * MUST be idempotent - payment gateways typically handle this.
     */
    public static JsonNode refundPayment(String idempotencyKey, JsonNode input) {
        String transactionId = input.has("transactionId") ? 
            input.get("transactionId").asText() : "UNKNOWN";
        
        log.info("[{}] COMPENSATION: Refunding payment: {}", idempotencyKey, transactionId);
        
        String refundId = "REF-" + UUID.randomUUID().toString().substring(0, 12);
        
        ObjectNode result = mapper.createObjectNode();
        result.put("refunded", true);
        result.put("originalTransactionId", transactionId);
        result.put("refundId", refundId);
        result.put("amount", input.has("amount") ? input.get("amount").asText() : "UNKNOWN");
        result.put("refundedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        result.put("reason", "Workflow compensation - order processing failed");
        
        log.info("[{}] COMPENSATION: Payment refunded: {} -> {}", 
            idempotencyKey, transactionId, refundId);
        return result;
    }
    
    /**
     * Ship Order Activity.
     */
    public static JsonNode shipOrder(String idempotencyKey, JsonNode input) {
        log.info("[{}] Creating shipment for order: {}", idempotencyKey, input.get("orderId"));
        
        String trackingNumber = "TRACK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        
        ObjectNode result = mapper.createObjectNode();
        result.put("shipped", true);
        result.put("orderId", input.get("orderId").asText());
        result.put("trackingNumber", trackingNumber);
        result.put("carrier", "FastShip");
        result.put("estimatedDelivery", Instant.now().plusSeconds(86400 * 3).toString()); // 3 days
        result.put("shippedAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        
        log.info("[{}] Order shipped: tracking {}", idempotencyKey, trackingNumber);
        return result;
    }
    
    /**
     * Send Confirmation Email Activity.
     */
    public static JsonNode sendConfirmation(String idempotencyKey, JsonNode input) {
        String email = input.has("customerEmail") ? 
            input.get("customerEmail").asText() : "customer@example.com";
        
        log.info("[{}] Sending confirmation email to: {}", idempotencyKey, email);
        
        String messageId = "MSG-" + UUID.randomUUID().toString().substring(0, 12);
        
        ObjectNode result = mapper.createObjectNode();
        result.put("sent", true);
        result.put("orderId", input.get("orderId").asText());
        result.put("messageId", messageId);
        result.put("recipient", email);
        result.put("sentAt", Instant.now().toString());
        result.put("idempotencyKey", idempotencyKey);
        
        log.info("[{}] Confirmation email sent: {}", idempotencyKey, messageId);
        return result;
    }
    
    // Configuration methods for failure simulation
    public static void enableInventoryFailures(int failCount) {
        simulateInventoryFailure = true;
        failuresBeforeSuccess = failCount;
        inventoryCheckAttempts.set(0);
    }
    
    public static void enablePaymentFailures(int failCount) {
        simulatePaymentFailure = true;
        failuresBeforeSuccess = failCount;
        paymentAttempts.set(0);
    }
    
    public static void disableFailures() {
        simulateInventoryFailure = false;
        simulatePaymentFailure = false;
        inventoryCheckAttempts.set(0);
        paymentAttempts.set(0);
    }
    
    public static void resetAttemptCounters() {
        inventoryCheckAttempts.set(0);
        paymentAttempts.set(0);
    }
    
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
