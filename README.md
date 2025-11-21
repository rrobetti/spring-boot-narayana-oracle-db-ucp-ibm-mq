# Spring Boot Narayana + Oracle UCP + IBM MQ Distributed Transactions

A complete Spring Boot 3.x demonstration of distributed XA transactions between IBM MQ and Oracle Database using Narayana JTA transaction manager and Oracle Universal Connection Pool (UCP).

## Overview

This project demonstrates exactly-once message processing semantics using distributed transactions across:
- **IBM MQ** (JMS messaging)
- **Oracle Database** (relational storage)
- **Narayana** (JTA transaction manager for 2-phase commit coordination)
- **Oracle UCP** (JDBC connection pooling)

### Architecture

The application implements a message processing pipeline:

1. **Consume**: Read JSON messages from IBM MQ input queue (`DEV.QUEUE.1`)
2. **Persist**: Store message data in Oracle database table (`MESSAGE_DATA`)
3. **Confirm**: Send confirmation JSON to IBM MQ output queue (`DEV.QUEUE.2`)

All three operations execute within a **single distributed XA transaction** coordinated by Narayana:
- If the database insert fails → MQ receive is rolled back (message remains on input queue)
- If the confirmation send fails → database insert is rolled back
- If any error occurs → complete transaction rollback ensures consistency

### Key Technologies

- **Spring Boot 3.3.5** with Java 17
- **Narayana JTA** (`me.snowdrop:narayana-spring-boot-starter:2.6.7`) for transaction management
- **Oracle UCP** (`oracle.ucp.jdbc.PoolXADataSource`) as the ONLY JDBC connection pool (no Hikari, no Atomikos)
- **IBM MQ 9.4** (`com.ibm.mq:com.ibm.mq.jakarta.client:9.4.0.0`) for Jakarta EE 9+ compatible JMS messaging
- **Spring Data JPA** for data access
- **Testcontainers** for integration testing with real Oracle and IBM MQ containers

## Comparison to Atomikos Version

This project is modeled after [spring-boot-atomikos-oracle-db-ibm-mq](https://github.com/rrobetti/spring-boot-atomikos-oracle-db-ibm-mq) but uses:

| Component | Atomikos Version | This (Narayana) Version |
|-----------|-----------------|-------------------------|
| JTA Provider | Atomikos | Narayana (Snowdrop starter) |
| JDBC Pool | Atomikos pool | Oracle UCP |
| DB XA Resource | AtomikosDataSourceBean | NarayanaDataSource wrapping UCP PoolXADataSource |
| Spring Boot Starter | N/A (manual config) | `me.snowdrop:narayana-spring-boot-starter` |
| IBM MQ Client | `com.ibm.mq.allclient` (javax) | `com.ibm.mq.jakarta.client` (Jakarta EE 9+) |

**Key Difference**: Narayana is used ONLY for transaction management and XA resource enlistment, not for connection pooling. Oracle UCP provides the actual connection pool.

## Project Structure

```
src/main/java/com/example/narayana/ucp/mqdemo/
├── Application.java                          # Main Spring Boot application
├── config/
│   ├── UcpNarayanaConfig.java               # Oracle UCP + Narayana integration
│   └── IBMmqConfig.java                     # IBM MQ XA configuration
├── model/
│   └── MessageData.java                     # JPA entity
├── repository/
│   └── MessageDataRepository.java           # Spring Data JPA repository
├── service/
│   └── MessageProcessingService.java        # Business logic with @Transactional
└── mq/
    └── MessageListener.java                 # JMS listener for input queue

src/test/java/com/example/narayana/ucp/mqdemo/
└── DistributedTransactionIntegrationTest.java # Testcontainers-based tests
```

## Configuration Details

### UCP + Narayana Integration (`UcpNarayanaConfig.java`)

1. **Creates `PoolXADataSource`** from Oracle UCP:
   - Uses `oracle.jdbc.xa.client.OracleXADataSource` as the connection factory
   - Configures pool size, timeouts, validation
   - This is the actual connection pool

2. **Wraps with `NarayanaDataSource`** (from Snowdrop starter):
   - Enables Narayana to enlist XA connections in distributed transactions
   - Does NOT introduce additional pooling
   - Marked as `@Primary` for auto-wiring

3. **Disables Hikari**:
   - Spring Boot's default DataSource auto-configuration is excluded
   - Ensures UCP is the only connection pool

### IBM MQ XA Configuration (`IBMmqConfig.java`)

1. **Creates `MQXAConnectionFactory`**:
   - Configures connection to IBM MQ with XA support
   - Sets queue manager, channel, host, credentials

2. **JmsTemplate**:
   - For sending messages to queues
   - Session is transacted (participates in JTA)

3. **XA JMS Listener Container Factory**:
   - Uses `JtaTransactionManager` from Narayana
   - Enables distributed transaction participation for message consumption
   - Configured for single-threaded, reliable processing

## Database Schema

The `MESSAGE_DATA` table:

```sql
CREATE TABLE MESSAGE_DATA (
    ID NUMBER PRIMARY KEY,
    MESSAGE_ID VARCHAR2(255) UNIQUE NOT NULL,
    MESSAGE_CONTENT VARCHAR2(4000),
    STATUS VARCHAR2(50),
    CREATED_AT TIMESTAMP
);
```

Spring Data JPA with Hibernate creates this automatically when `spring.jpa.hibernate.ddl-auto=update`.

## Message Format

### Input Message (DEV.QUEUE.1)

```json
{
  "messageId": "MSG-001",
  "content": "Test message content",
  "status": "NEW"
}
```

### Confirmation Message (DEV.QUEUE.2)

```json
{
  "messageId": "MSG-001",
  "status": "PROCESSED",
  "timestamp": "2025-11-06T09:00:00Z"
}
```

## Running the Application

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker (for Testcontainers or local Oracle/IBM MQ)

### Local Development

1. **Start Oracle Database** (e.g., Docker):
   ```bash
   docker run -d -p 1521:1521 -e ORACLE_PASSWORD=oracle gvenzl/oracle-xe:21-slim-faststart
   ```

2. **Start IBM MQ** (Docker):
   ```bash
   docker run -d -p 1414:1414 -p 9443:9443 \
     -e LICENSE=accept \
     -e MQ_QMGR_NAME=QM1 \
     -e MQ_APP_PASSWORD=passw0rd \
     icr.io/ibm-messaging/mq:latest
   ```

3. **Build the project**:
   ```bash
   mvn clean package
   ```

4. **Run the application**:
   ```bash
   mvn spring-boot:run
   ```

5. **Send a test message**:
   Use IBM MQ Explorer, `amqsput`, or JMS client to send JSON to `DEV.QUEUE.1`

### Configuration

Edit `src/main/resources/application.yml` to configure:

- **Oracle connection**: URL, username, password, pool sizes
- **IBM MQ connection**: queue manager, channel, host, credentials
- **Narayana settings**: transaction timeout, log directory

## Running Tests

The integration tests use Testcontainers to automatically start Oracle and IBM MQ containers.

### Run all tests:

```bash
mvn test
```

### What the tests verify:

1. **Successful processing**: Message consumed → DB insert → confirmation sent
2. **Database failure rollback**: Constraint violation → no DB commit, message returns to queue
3. **Multiple messages**: Proper handling of concurrent/sequential messages

The tests demonstrate that Narayana coordinates proper 2-phase commit (2PC) across Oracle (via UCP) and IBM MQ.

## Transaction Flow

### Happy Path (Commit)

```
1. JMS Container: Receive message (XA start)
2. Narayana: Begin distributed transaction
3. Service: Parse message
4. UCP/Oracle: Insert into MESSAGE_DATA (XA resource enlisted)
5. IBM MQ: Send confirmation to output queue (XA resource enlisted)
6. Narayana: 2PC Prepare phase → both resources vote "yes"
7. Narayana: 2PC Commit phase → both resources commit
8. JMS Container: Acknowledge message
```

### Error Path (Rollback)

```
1. JMS Container: Receive message (XA start)
2. Narayana: Begin distributed transaction
3. Service: Parse message
4. UCP/Oracle: Insert into MESSAGE_DATA (XA resource enlisted)
5. Service: Exception thrown (e.g., duplicate key)
6. Narayana: Rollback distributed transaction
   - Oracle: Rollback (no data committed)
   - IBM MQ: Rollback (message not acknowledged)
7. JMS Container: Message redelivered or moved to DLQ
```

## Important Notes

### Oracle UCP

- **Oracle UCP** is the ONLY connection pool in this application
- **Narayana** does NOT pool connections; it only manages transactions
- `NarayanaDataSource` (from Snowdrop) wraps UCP's `PoolXADataSource` to enable XA enlistment
- HikariCP is explicitly disabled via Spring Boot auto-configuration exclusion

### Transaction Management

- Use `@Transactional` on service methods to participate in JTA transactions
- The JMS listener container automatically starts a JTA transaction for each message
- Both database operations and JMS sends/receives participate in the same distributed transaction

### Narayana Recovery

- Narayana maintains transaction logs in `${spring.jta.narayana.log-dir}`
- In case of crash during 2PC, Narayana recovery manager completes pending transactions on restart
- Each node should have a unique `transaction-manager-id`

## Troubleshooting

### Issue: HikariCP is being used

**Solution**: Ensure `DataSourceAutoConfiguration` is excluded in `application.yml` or `@SpringBootApplication` annotation.

### Issue: Transaction not rolling back

**Solution**: 
- Verify `@Transactional` annotation is present
- Ensure exceptions are not caught and swallowed
- Check that listener container factory uses `JtaTransactionManager`

### Issue: Oracle XA errors

**Solution**:
- Verify Oracle user has XA permissions: `GRANT SELECT ON sys.dba_pending_transactions TO <user>;`
- Check UCP configuration: `setConnectionFactoryClassName("oracle.jdbc.xa.client.OracleXADataSource")`

### Issue: IBM MQ connection failures

**Solution**:
- Verify queue manager is running and accessible
- Check channel permissions for the user
- Ensure `MQXAConnectionFactory` is configured correctly

## License

This project is released under the MIT License (same as the reference Atomikos project).

## References

- [Narayana Documentation](https://narayana.io/)
- [Oracle UCP Guide](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjucp/)
- [IBM MQ Documentation](https://www.ibm.com/docs/en/ibm-mq)
- [Spring Boot JTA](https://docs.spring.io/spring-boot/docs/current/reference/html/data.html#data.distributed-transactions)
- [Reference Atomikos Project](https://github.com/rrobetti/spring-boot-atomikos-oracle-db-ibm-mq) 
