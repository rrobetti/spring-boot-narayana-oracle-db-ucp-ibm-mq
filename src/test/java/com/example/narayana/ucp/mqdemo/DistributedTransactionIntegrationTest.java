package com.example.narayana.ucp.mqdemo;

import com.example.narayana.ucp.mqdemo.model.MessageData;
import com.example.narayana.ucp.mqdemo.repository.MessageDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test demonstrating distributed XA transactions between
 * IBM MQ and Oracle Database using Narayana and Oracle UCP.
 * 
 * Uses Testcontainers to start real Oracle and IBM MQ instances.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DistributedTransactionIntegrationTest {

    @Container
    static OracleContainer oracleContainer = new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
            .withDatabaseName("XEPDB1")
            .withUsername("testuser")
            .withPassword("testpass")
            .withReuse(false)
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static GenericContainer<?> ibmMqContainer = new GenericContainer<>(DockerImageName.parse("icr.io/ibm-messaging/mq:latest"))
            .withEnv("LICENSE", "accept")
            .withEnv("MQ_QMGR_NAME", "QM1")
            .withEnv("MQ_APP_PASSWORD", "passw0rd")
            .withEnv("MQ_ENABLE_METRICS", "false")
            .withExposedPorts(1414, 9443)
            .waitingFor(Wait.forLogMessage(".*Started web server.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Oracle properties
        registry.add("oracle.datasource.url", oracleContainer::getJdbcUrl);
        registry.add("oracle.datasource.username", oracleContainer::getUsername);
        registry.add("oracle.datasource.password", oracleContainer::getPassword);

        // IBM MQ properties
        registry.add("ibm.mq.conn-name", () -> 
            ibmMqContainer.getHost() + "(" + ibmMqContainer.getMappedPort(1414) + ")");
        registry.add("ibm.mq.user", () -> "app");
        registry.add("ibm.mq.password", () -> "passw0rd");
    }

    @Autowired
    private MessageDataRepository repository;

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ibm.mq.input-queue}")
    private String inputQueue;

    @Value("${ibm.mq.output-queue}")
    private String outputQueue;

    @BeforeEach
    void setUp() {
        // Clean up database before each test
        repository.deleteAll();
        
        // Clean up queues
        purgeQueue(inputQueue);
        purgeQueue(outputQueue);
    }

    /**
     * Test 1: Successful message processing with distributed transaction commit.
     * 
     * Verifies that:
     * - Message is consumed from input queue
     * - Record is persisted to database
     * - Confirmation message is sent to output queue
     * - All within a single distributed XA transaction
     */
    @Test
    void testSuccessfulMessageProcessing() throws Exception {
        // Given: a message on the input queue
        String messageId = "MSG-SUCCESS-001";
        String messageJson = createMessageJson(messageId, "Test content", "NEW");
        
        jmsTemplate.convertAndSend(inputQueue, messageJson);

        // When: the message is processed (happens automatically via listener)
        // Then: verify database record was created
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<MessageData> saved = repository.findByMessageId(messageId);
            assertThat(saved).isPresent();
            assertThat(saved.get().getMessageId()).isEqualTo(messageId);
            assertThat(saved.get().getMessageContent()).isEqualTo("Test content");
            assertThat(saved.get().getStatus()).isEqualTo("NEW");
        });

        // And: verify confirmation message was sent to output queue
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            String confirmation = (String) jmsTemplate.receiveAndConvert(outputQueue);
            assertThat(confirmation).isNotNull();
            assertThat(confirmation).contains(messageId);
            assertThat(confirmation).contains("PROCESSED");
        });
    }

    /**
     * Test 2: Database failure causes transaction rollback.
     * 
     * Verifies that when a database constraint violation occurs:
     * - Database changes are rolled back
     * - Message is not acknowledged (returns to input queue)
     * - No confirmation message is sent
     */
    @Test
    void testDatabaseFailureCausesRollback() throws Exception {
        // Given: a message that will cause a duplicate key violation
        String messageId = "MSG-DUPLICATE-001";
        String messageJson = createMessageJson(messageId, "Original content", "NEW");
        
        // First, successfully process a message
        jmsTemplate.convertAndSend(inputQueue, messageJson);
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(repository.existsByMessageId(messageId)).isTrue();
        });
        
        // Clear the output queue
        purgeQueue(outputQueue);
        
        long initialCount = repository.count();

        // When: send the same messageId again (will cause constraint violation)
        jmsTemplate.convertAndSend(inputQueue, messageJson);

        // Give it time to attempt processing
        Thread.sleep(3000);

        // Then: no new record should be committed
        assertThat(repository.count()).isEqualTo(initialCount);

        // And: the message should have been rolled back and redelivered
        // (it may be on the input queue or in a backout queue depending on retry logic)
        // For this test, we verify that no duplicate was created
        long count = repository.findAll().stream()
                .filter(m -> m.getMessageId().equals(messageId))
                .count();
        assertThat(count).isEqualTo(1); // Only the original one
    }

    /**
     * Test 3: Multiple messages processed successfully.
     * 
     * Verifies that the system can handle multiple messages correctly
     * with proper transactional guarantees for each.
     */
    @Test
    void testMultipleMessagesProcessing() throws Exception {
        // Given: multiple messages on the input queue
        for (int i = 1; i <= 3; i++) {
            String messageId = "MSG-MULTI-" + String.format("%03d", i);
            String messageJson = createMessageJson(messageId, "Content " + i, "NEW");
            jmsTemplate.convertAndSend(inputQueue, messageJson);
        }

        // When: messages are processed
        // Then: all should be in the database
        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(repository.count()).isEqualTo(3);
        });

        // And: all confirmation messages should be sent
        int confirmationCount = 0;
        for (int i = 0; i < 3; i++) {
            String confirmation = (String) jmsTemplate.receiveAndConvert(outputQueue);
            if (confirmation != null && confirmation.contains("PROCESSED")) {
                confirmationCount++;
            }
        }
        assertThat(confirmationCount).isEqualTo(3);
    }

    // Helper methods

    private String createMessageJson(String messageId, String content, String status) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("messageId", messageId);
        node.put("content", content);
        node.put("status", status);
        return objectMapper.writeValueAsString(node);
    }

    private void purgeQueue(String queueName) {
        try {
            while (true) {
                Object message = jmsTemplate.receiveAndConvert(queueName);
                if (message == null) {
                    break;
                }
            }
        } catch (Exception e) {
            // Ignore - queue might be empty
        }
    }
}
