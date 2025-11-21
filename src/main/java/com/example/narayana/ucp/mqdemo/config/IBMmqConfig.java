package com.example.narayana.ucp.mqdemo.config;

import com.ibm.mq.jakarta.jms.MQXAConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.jta.JtaTransactionManager;

/**
 * Configuration for IBM MQ with XA transaction support via Narayana.
 * 
 * This configuration:
 * 1. Creates an MQXAConnectionFactory for XA-enabled JMS connections
 * 2. Configures JmsTemplate for sending messages
 * 3. Configures JMS listener container factory with JTA transaction support
 */
@Configuration
public class IBMmqConfig {

    @Value("${ibm.mq.queue-manager}")
    private String queueManager;

    @Value("${ibm.mq.channel}")
    private String channel;

    @Value("${ibm.mq.conn-name}")
    private String connName;

    @Value("${ibm.mq.user}")
    private String user;

    @Value("${ibm.mq.password}")
    private String password;

    /**
     * Creates the IBM MQ XA Connection Factory.
     * This factory creates XA-enabled JMS connections that can participate
     * in distributed transactions managed by Narayana.
     */
    @Bean
    public ConnectionFactory mqConnectionFactory() throws Exception {
        MQXAConnectionFactory factory = new MQXAConnectionFactory();
        
        // Connection properties
        factory.setQueueManager(queueManager);
        factory.setChannel(channel);
        factory.setConnectionNameList(connName);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        
        // Authentication
        factory.setStringProperty(WMQConstants.USERID, user);
        factory.setStringProperty(WMQConstants.PASSWORD, password);
        
        // Set app name for identification
        factory.setAppName("narayana-ucp-mq-demo");
        
        return factory;
    }

    /**
     * JmsTemplate for sending messages to IBM MQ queues.
     * Uses the XA connection factory and participates in JTA transactions.
     */
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory mqConnectionFactory) {
        JmsTemplate template = new JmsTemplate(mqConnectionFactory);
        template.setSessionTransacted(true); // Enable transacted sessions
        return template;
    }

    /**
     * JMS Listener Container Factory for consuming messages with XA transaction support.
     * This factory creates listener containers that:
     * - Use XA connections from IBM MQ
     * - Participate in JTA transactions managed by Narayana
     * - Support distributed 2PC across MQ and database
     */
    @Bean
    public DefaultJmsListenerContainerFactory xaJmsListenerContainerFactory(
            ConnectionFactory mqConnectionFactory,
            JtaTransactionManager transactionManager,
            DefaultJmsListenerContainerFactoryConfigurer configurer) {
        
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        
        // Apply Spring Boot defaults
        configurer.configure(factory, mqConnectionFactory);
        
        // Enable XA transaction support with Narayana JTA
        factory.setTransactionManager(transactionManager);
        factory.setSessionTransacted(true);
        
        // Configure for reliable message processing
        factory.setConcurrency("1-1"); // Single threaded for simplicity
        factory.setReceiveTimeout(5000L);
        
        return factory;
    }
}
