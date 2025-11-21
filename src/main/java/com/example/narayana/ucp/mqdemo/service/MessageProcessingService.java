package com.example.narayana.ucp.mqdemo.service;

import com.example.narayana.ucp.mqdemo.model.MessageData;
import com.example.narayana.ucp.mqdemo.repository.MessageDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Service that processes messages in a distributed XA transaction.
 * 
 * The @Transactional annotation ensures that:
 * 1. Message receive from IBM MQ
 * 2. Database insert
 * 3. Confirmation message send to IBM MQ
 * All happen within a single distributed transaction managed by Narayana.
 * 
 * If any step fails, the entire transaction is rolled back:
 * - DB changes are undone
 * - Input message returns to the queue
 * - Output message is not sent
 */
@Service
public class MessageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessingService.class);

    private final MessageDataRepository repository;
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ibm.mq.output-queue}")
    private String outputQueue;

    public MessageProcessingService(MessageDataRepository repository, 
                                   JmsTemplate jmsTemplate,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes an incoming message in a distributed XA transaction.
     * 
     * @param messageJson JSON string containing the message
     * @throws Exception if processing fails (triggers transaction rollback)
     */
    @Transactional
    public void processMessage(String messageJson) throws Exception {
        log.info("Processing message: {}", messageJson);

        // Parse the incoming JSON message
        JsonNode jsonNode = objectMapper.readTree(messageJson);
        String messageId = jsonNode.get("messageId").asText();
        String content = jsonNode.get("content").asText();
        String status = jsonNode.get("status").asText();

        log.debug("Parsed message - ID: {}, Content: {}, Status: {}", messageId, content, status);

        // Check for duplicate messages
        if (repository.existsByMessageId(messageId)) {
            log.warn("Duplicate message detected: {}. Skipping processing.", messageId);
            throw new IllegalStateException("Duplicate message: " + messageId);
        }

        // Persist to database (within XA transaction)
        MessageData messageData = new MessageData(messageId, content, status);
        repository.save(messageData);
        log.info("Saved message to database: {}", messageData);

        // Create confirmation message
        ObjectNode confirmationNode = objectMapper.createObjectNode();
        confirmationNode.put("messageId", messageId);
        confirmationNode.put("status", "PROCESSED");
        confirmationNode.put("timestamp", Instant.now().toString());
        String confirmationJson = objectMapper.writeValueAsString(confirmationNode);

        // Send confirmation to output queue (within XA transaction)
        jmsTemplate.convertAndSend(outputQueue, confirmationJson);
        log.info("Sent confirmation message to {}: {}", outputQueue, confirmationJson);

        log.info("Message processing completed successfully for messageId: {}", messageId);
    }

    /**
     * Processes a message and forces a failure for testing rollback behavior.
     * 
     * @param messageJson JSON string containing the message
     * @param failureType Type of failure to simulate (db, jms, or general)
     * @throws Exception always throws to trigger rollback
     */
    @Transactional
    public void processMessageWithFailure(String messageJson, String failureType) throws Exception {
        log.info("Processing message with forced failure (type: {}): {}", failureType, messageJson);

        // Parse the incoming JSON message
        JsonNode jsonNode = objectMapper.readTree(messageJson);
        String messageId = jsonNode.get("messageId").asText();
        String content = jsonNode.get("content").asText();
        String status = jsonNode.get("status").asText();

        if ("db".equals(failureType)) {
            // This will cause a DB constraint violation (duplicate messageId)
            MessageData messageData = new MessageData(messageId, content, status);
            repository.save(messageData);
            log.info("Saved message to database: {}", messageData);
            
            // Try to save again - should cause unique constraint violation
            MessageData duplicate = new MessageData(messageId, content + "_duplicate", status);
            repository.save(duplicate);
            repository.flush(); // Force the constraint check
        } else if ("jms".equals(failureType)) {
            // Save to DB first
            MessageData messageData = new MessageData(messageId, content, status);
            repository.save(messageData);
            log.info("Saved message to database: {}", messageData);
            
            // Then throw an exception before sending confirmation
            throw new RuntimeException("Simulated JMS failure after DB insert");
        } else {
            // General failure before any work
            throw new RuntimeException("Simulated general failure");
        }
    }
}
