package com.example.narayana.ucp.mqdemo.config;

import oracle.ucp.jdbc.PoolXADataSource;
import oracle.ucp.jdbc.PoolXADataSourceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import me.snowdrop.boot.narayana.core.jdbc.NarayanaDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Configuration for Oracle UCP (Universal Connection Pool) with Narayana XA support.
 * 
 * This configuration:
 * 1. Creates a PoolXADataSource from Oracle UCP as the connection pool
 * 2. Wraps it with NarayanaDataSource for XA transaction enlistment
 * 3. Ensures UCP is the ONLY connection pool (no Hikari, no Atomikos pool)
 */
@Configuration
public class UcpNarayanaConfig {

    @Value("${oracle.datasource.url}")
    private String url;

    @Value("${oracle.datasource.username}")
    private String username;

    @Value("${oracle.datasource.password}")
    private String password;

    @Value("${oracle.datasource.initial-pool-size:5}")
    private int initialPoolSize;

    @Value("${oracle.datasource.min-pool-size:5}")
    private int minPoolSize;

    @Value("${oracle.datasource.max-pool-size:20}")
    private int maxPoolSize;

    /**
     * Creates the Oracle UCP PoolXADataSource.
     * This is the actual connection pool that manages database connections.
     */
    @Bean
    public PoolXADataSource poolXADataSource() throws SQLException {
        PoolXADataSourceImpl pds = new PoolXADataSourceImpl();
        
        // Set the XA data source factory class
        pds.setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource");
        
        // Database connection properties
        pds.setURL(url);
        pds.setUser(username);
        pds.setPassword(password);
        
        // Pool sizing
        pds.setInitialPoolSize(initialPoolSize);
        pds.setMinPoolSize(minPoolSize);
        pds.setMaxPoolSize(maxPoolSize);
        
        // Connection validation
        pds.setValidateConnectionOnBorrow(true);
        pds.setConnectionValidationTimeout(5);
        
        // Timeouts
        pds.setInactiveConnectionTimeout(300);
        pds.setTimeoutCheckInterval(30);
        pds.setAbandonedConnectionTimeout(300);
        
        // XA properties
        pds.setConnectionProperty("oracle.net.keepAlive", "true");
        
        return pds;
    }

    /**
     * Wraps the UCP PoolXADataSource with NarayanaDataSource.
     * This wrapper enables Narayana to enlist XA connections in distributed transactions
     * without introducing additional connection pooling.
     * 
     * Marked as @Primary so Spring uses this everywhere (JPA, JDBC, etc.)
     */
    @Bean
    @Primary
    public DataSource dataSource(PoolXADataSource poolXADataSource) {
        return new NarayanaDataSource(poolXADataSource);
    }
}
