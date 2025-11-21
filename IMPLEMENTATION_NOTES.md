# Implementation Notes

## Project Overview

This project implements a complete Spring Boot 3.x application demonstrating distributed XA transactions between IBM MQ and Oracle Database using Narayana JTA and Oracle UCP.

## Key Implementation Decisions

### 1. Narayana JTA Implementation

**Decision**: Used `me.snowdrop:narayana-spring-boot-starter:2.6.7` instead of a non-existent `spring-boot-starter-jta-narayana`.

**Rationale**: Spring Boot 3.x does not provide an official Narayana starter. The Snowdrop Narayana starter is the community-maintained, production-ready solution that provides:
- Full integration with Spring Boot auto-configuration
- `NarayanaDataSource` wrapper for XA datasource enlistment
- Automatic JTA transaction manager configuration
- Compatible with Spring Boot 3.3.5

### 2. Oracle UCP Configuration

**Implementation**: 
- Created `PoolXADataSource` from Oracle UCP as the actual connection pool
- Wrapped it with `NarayanaDataSource` (from Snowdrop) for XA enlistment
- Configured as `@Primary` bean to ensure it's used everywhere

**Key Points**:
- UCP is the ONLY connection pool (HikariCP explicitly disabled)
- Narayana does NOT pool connections; it only manages XA transactions
- Connection pooling parameters configured on UCP: initial, min, max pool sizes

### 3. IBM MQ Jakarta Client

**Decision**: Used `com.ibm.mq:com.ibm.mq.jakarta.client:9.4.0.0` instead of `com.ibm.mq.allclient`.

**Rationale**: Spring Boot 3.x requires Jakarta EE 9+ (jakarta.* packages). The `allclient` artifact uses javax.* packages which are incompatible. The `jakarta.client` artifact provides:
- Jakarta JMS 3.0 compatibility
- `MQXAConnectionFactory` with jakarta.jms interfaces
- Full XA transaction support with Narayana

### 4. Spring JMS Configuration

**Removed**: `spring-boot-starter-artemis` (as per code review)

**Added**: Direct `spring-jms` dependency

**Rationale**: The Artemis starter was unnecessary and could cause classpath conflicts. IBM MQ provides its own JMS implementation, and Spring JMS provides the necessary abstractions (JmsTemplate, @JmsListener) without Artemis.

### 5. Test Implementation

**Approach**: Testcontainers-based integration tests with:
- Real Oracle XE container (`gvenzl/oracle-xe:21-slim-faststart`)
- Real IBM MQ container (`icr.io/ibm-messaging/mq:latest`)
- Awaitility for robust asynchronous assertions
- Dynamic property configuration for container URLs/ports

**Test Scenarios**:
1. Successful message processing with distributed commit
2. Database constraint violation causing full transaction rollback
3. Multiple message processing

## Architecture Highlights

### XA Transaction Flow

```
1. JMS Container receives message (XA start)
2. Narayana begins distributed transaction
3. Service processes message:
   a. UCP provides pooled XA connection (enlisted by Narayana)
   b. JPA persists to Oracle via UCP connection
   c. JmsTemplate sends confirmation via MQ XA connection (enlisted by Narayana)
4. Narayana executes 2PC:
   a. Prepare phase: Oracle and MQ vote
   b. Commit phase: Both commit or both rollback
5. JMS Container acknowledges original message
```

### Key Configuration Files

- **pom.xml**: Maven dependencies including Snowdrop Narayana, UCP, IBM MQ Jakarta
- **application.yml**: Oracle UCP config, IBM MQ config, Narayana JTA config
- **UcpNarayanaConfig.java**: UCP + Narayana integration
- **IBMmqConfig.java**: IBM MQ XA connection factory and JMS listener factory
- **MessageListener.java**: JMS listener with XA transaction support
- **MessageProcessingService.java**: @Transactional service coordinating DB + JMS

## Build and Package

The project successfully:
- Compiles all Java sources
- Packages as executable Spring Boot JAR
- Includes all dependencies in BOOT-INF/lib
- Passes CodeQL security scan (0 vulnerabilities)

## Running the Application

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker (for Oracle and IBM MQ)

### Build
```bash
mvn clean package
```

### Run
```bash
java -jar target/spring-boot-narayana-oracle-db-ucp-ibm-mq-1.0.0-SNAPSHOT.jar
```

### Run Tests
```bash
mvn test
```

Note: Tests require Docker for Testcontainers.

## Comparison to Reference Project

Modeled after `rrobetti/spring-boot-atomikos-oracle-db-ibm-mq` but with key differences:

| Aspect | Atomikos Version | This Version |
|--------|------------------|--------------|
| JTA Provider | Atomikos | Narayana (Snowdrop) |
| Connection Pool | Atomikos pool | Oracle UCP |
| XA Wrapper | AtomikosDataSourceBean | NarayanaDataSource |
| Configuration | Manual | Spring Boot auto-configuration |
| IBM MQ Client | javax-based | jakarta-based |

## Known Limitations

1. Tests require Docker and sufficient resources to run Oracle + IBM MQ containers
2. Application startup requires both Oracle and IBM MQ to be available
3. Single-threaded JMS processing (concurrency="1-1") for simplicity

## Future Enhancements

1. Add health checks for Oracle and IBM MQ
2. Implement circuit breaker for external dependencies
3. Add metrics and monitoring (Micrometer)
4. Implement dead letter queue handling
5. Add support for multiple queue managers
