package com.example.narayana.ucp.mqdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Boot application demonstrating distributed XA transactions
 * between IBM MQ and Oracle Database using Narayana JTA and Oracle UCP.
 */
@SpringBootApplication
@EnableJms
@EnableTransactionManagement
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
