package com.example.narayana.ucp.mqdemo.mq;

import com.example.narayana.ucp.mqdemo.service.MessageProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * JMS listener that consumes messages from IBM MQ input queue
 * and processes them within distributed XA transactions.
 * 
 * The listener uses the xaJmsListenerContainerFactory which is configured
 * with Narayana's JTA transaction manager, enabling distributed 2PC
 * across IBM MQ and Oracle Database.
 */
@Component
public class MessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessageListener.class);

    private final MessageProcessingService messageProcessingService;

    public MessageListener(MessageProcessingService messageProcessingService) {
        this.messageProcessingService = messageProcessingService;
    }

    /**
     * Listens to messages from the input queue and delegates to the service.
     * 
     * The entire operation (receive → process → send) happens in a single
     * distributed XA transaction. If an exception is thrown:
     * - The transaction is rolled back
     * - The message returns to the input queue
     * - No database changes are committed
     * - No confirmation message is sent
     * 
     * @param messageJson JSON message from the queue
     */
    @JmsListener(
        destination = "${ibm.mq.input-queue}",
        containerFactory = "xaJmsListenerContainerFactory"
    )
    public void onMessage(String messageJson) {
        log.info("Received message from input queue: {}", messageJson);
        
        try {
            messageProcessingService.processMessage(messageJson);
            log.info("Message processed successfully");
        } catch (Exception e) {
            log.error("Error processing message, transaction will be rolled back: {}", e.getMessage(), e);
            // Exception will cause transaction rollback by JMS container
            throw new RuntimeException("Message processing failed", e);
        }
    }
}
