# Local Write Forwarding Integration Guide

## Overview

This document explains how to integrate Aurora PostgreSQL local write forwarding support with the Spring Hibernate Balanced Reader example.

## What is Local Write Forwarding?

Local write forwarding is a feature in Aurora PostgreSQL (14.13+, 15.8+, 16.4+, 17+) that allows read replicas to accept write requests. These writes are automatically forwarded to the writer instance, simplifying application architecture and enabling read-after-write consistency.

## Key Benefits

1. **Simplified Architecture**: Use a single connection endpoint instead of managing separate read/write connections
2. **Read-After-Write Consistency**: No need for complex application logic
3. **Availability Zone Optimization**: Route to readers in the same AZ for lower latency
4. **Automatic DDL Handling**: DDL statements automatically routed to writer

## Prerequisites

- Aurora PostgreSQL 14.13+, 15.8+, 16.4+, or 17+
- Cluster with `EnableLocalWriteForwarding = true`
- AWS Advanced JDBC Wrapper 2.4.0+

## How to Enable

### Step 1: Enable Local Write Forwarding on Your Aurora Cluster

Using AWS CLI:
```bash
aws rds modify-db-cluster \
    --db-cluster-identifier your-cluster \
    --enable-local-write-forwarding
```

Using AWS Console:
1. Navigate to RDS > Databases > Your Cluster
2. Click "Modify"
3. Under "Additional configuration", check "Turn on local read replica write forwarding"
4. Apply changes

### Step 2: Configure Connection String

Add the local write forwarding parameters to your connection string:

```yaml
spring:
  datasource:
    url: jdbc:aws-wrapper:postgresql://your-cluster.cluster-xyz.us-east-2.rds.amazonaws.com:5432/postgres?enableLocalWriteForwarding=true&localWriteForwardingConsistencyMode=SESSION&enableDDLDetection=true&wrapperPlugins=localWriteForwarding,readWriteSplitting,failover2
```

### Step 3: Configure Consistency Mode

Choose the appropriate consistency mode for your application:

| Mode | Description | Use Case |
|------|-------------|----------|
| **SESSION** (default) | Queries see results of all changes in current session | Most applications - good balance |
| **EVENTUAL** | May see slightly stale data | Read-heavy workloads, latency-sensitive |
| **GLOBAL** | See all committed changes cluster-wide | Strong consistency requirements |
| **OFF** | Disable write forwarding | Fallback mode |

## Configuration Properties

```properties
# Enable local write forwarding support
enableLocalWriteForwarding=true

# Set consistency mode (SESSION, EVENTUAL, GLOBAL, OFF)
localWriteForwardingConsistencyMode=SESSION

# Enable automatic DDL detection
enableDDLDetection=true

# Plugin chain for local write forwarding
wrapperPlugins=localWriteForwarding,readWriteSplitting,failover2,efm2
```

## Example Configuration

### Full application.yml Example

```yaml
spring:
  profiles.active: development
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

logging:
  level:
    software:
      amazon:
        jdbc: INFO
        jdbc.plugin.localwriteforwarding: DEBUG
    example: TRACE

---
spring:
  config.active.on-profile: development
  datasource:
    load-balanced-writer-and-reader-datasource:
      url: jdbc:aws-wrapper:postgresql://test-cluster.cluster-XYZ.us-east-2.rds.amazonaws.com:5432/postgres?enableLocalWriteForwarding=true&localWriteForwardingConsistencyMode=SESSION&enableDDLDetection=true&wrapperPlugins=localWriteForwarding,readWriteSplitting,failover2&readerHostSelectorStrategy=roundRobin
      username: dev_user
      password: dev_password
      driver-class-name: software.amazon.jdbc.Driver
      type: org.springframework.jdbc.datasource.SimpleDriverDataSource
```

## How It Works

### Connection Routing Logic

```
Application Request
        │
        ▼
  Is LWF enabled?
        │
    ┌───┴───┐
   NO      YES
    │       │
    │       ▼
    │  Is DDL Statement?
    │       │
    │   ┌───┴───┐
    │  YES     NO
    │   │       │
    │   ▼       ▼
    │ WRITER  Same AZ Reader?
    │           │
    │       ┌───┴───┐
    │      YES     NO
    │       │       │
    │       ▼       ▼
    │  Same AZ   Fastest
    │  Reader    Reader
    │
    ▼
 Standard
 Routing
```

### Automatic DDL Detection

The plugin automatically detects and routes these statements to the writer:
- CREATE, ALTER, DROP
- TRUNCATE, RENAME
- GRANT, REVOKE
- ANALYZE, CLUSTER, VACUUM
- And more (see design doc for full list)

### Session State Management

On failover, the plugin automatically:
1. Restores connection to healthy instance
2. Reapplies `apg_write_forward.consistency_mode`
3. Maintains transaction state
4. Preserves other session settings

## Testing Your Setup

### 1. Verify Cluster Has LWF Enabled

```sql
-- Connect to any instance in the cluster
SELECT setting 
FROM pg_settings 
WHERE name = 'apg_write_forward.consistency_mode';
-- Should return: SESSION (or your configured mode)
```

### 2. Test DDL Routing

```java
// This should be executed on the writer instance
connection.execute("CREATE TABLE test_lwf (id INT)");
```

### 3. Test DML with Write Forwarding

```java
// This can be executed on a reader with LWF
connection.execute("INSERT INTO test_lwf VALUES (1)");
connection.execute("SELECT * FROM test_lwf");
// Should see the inserted row immediately due to SESSION consistency
```

## Limitations

### Unsupported Statements

These statements **cannot** use write forwarding:
- DDL statements (automatically routed to writer by plugin)
- SAVEPOINT
- Cursors
- Two-phase commit
- User-defined functions/procedures
- COPY, LOCK, LISTEN/NOTIFY

### Isolation Levels

- ✅ Supported: REPEATABLE READ, READ COMMITTED
- ❌ Not Supported: SERIALIZABLE

### Other Limitations

- Not compatible with RDS Proxy
- Requires Aurora PostgreSQL 14.13+, 15.8+, 16.4+, or 17+
- Maximum forwarding connections limited by `apg_write_forward.max_forwarding_connections_percent`

## Troubleshooting

### Connection Fails with "parameter not found"

**Problem**: `apg_write_forward.consistency_mode` parameter not found

**Solution**: Verify cluster has local write forwarding enabled:
```bash
aws rds describe-db-clusters \
    --db-cluster-identifier your-cluster \
    --query 'DBClusters[0].LocalWriteForwardingStatus'
```

### Writes Not Being Forwarded

**Problem**: DML statements not using write forwarding

**Solutions**:
1. Check `enableLocalWriteForwarding=true` in connection string
2. Verify consistency mode is not `OFF`
3. Ensure connected to a reader instance
4. Check Aurora PostgreSQL version (14.13+, 15.8+, 16.4+, or 17+)

### High Latency on Writes

**Problem**: Forwarded writes are slower than expected

**Solutions**:
1. Consider using `EVENTUAL` consistency mode for lower latency
2. Route write-heavy transactions to writer directly
3. Check network latency between reader and writer
4. Monitor `apg_write_forward.max_forwarding_connections_percent`

## Performance Tuning

### Consistency Mode Selection

- **EVENTUAL**: Lowest latency, may see stale reads
- **SESSION**: Balanced - recommended for most use cases
- **GLOBAL**: Highest latency, strongest consistency

### Connection Pool Settings

```java
// In Config.java
final HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(30);
config.setMinimumIdle(2);
config.setIdleTimeout(TimeUnit.MINUTES.toMillis(15));
config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(3));
```

### Monitoring

Enable detailed logging:
```yaml
logging:
  level:
    software.amazon.jdbc.plugin.localwriteforwarding: DEBUG
```

Monitor these metrics:
- `AuroraLocalWriteForwardingDMLStatementsPerSecond`
- `AuroraLocalWriteForwardingCommitLatency`
- `AuroraLocalWriteForwardingCommitThroughput`

## Migration from Standard Read/Write Splitting

### Before (Standard)
```yaml
url: jdbc:aws-wrapper:postgresql://cluster.rds.amazonaws.com:5432/db?wrapperPlugins=readWriteSplitting,failover2
```

### After (With LWF)
```yaml
url: jdbc:aws-wrapper:postgresql://cluster.rds.amazonaws.com:5432/db?wrapperPlugins=localWriteForwarding,readWriteSplitting,failover2&enableLocalWriteForwarding=true&localWriteForwardingConsistencyMode=SESSION
```

### Key Changes

1. Add `localWriteForwarding` to plugin chain (before readWriteSplitting)
2. Add `enableLocalWriteForwarding=true`
3. Optionally set `localWriteForwardingConsistencyMode`
4. Ensure cluster has LWF enabled

## Best Practices

1. **Use SESSION Consistency**: Best balance for most applications
2. **Enable DDL Detection**: Ensures DDL statements go to writer
3. **Monitor Metrics**: Watch forwarding throughput and latency
4. **Test Failover**: Verify session state restoration works correctly
5. **Plan for Limits**: Consider `max_forwarding_connections_percent` setting
6. **Document Statement Types**: Know which statements can use LWF

## Additional Resources

- [Design Document](../../docs/using-the-jdbc-driver/using-plugins/LocalWriteForwardingDesign.md)
- [Aurora PostgreSQL Local Write Forwarding](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding.html)
- [AWS Advanced JDBC Wrapper Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper)

## Support

For issues or questions:
1. Check the [Design Document](../../docs/using-the-jdbc-driver/using-plugins/LocalWriteForwardingDesign.md)
2. Review [Aurora PostgreSQL Documentation](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding.html)
3. Open an issue on [GitHub](https://github.com/aws/aws-advanced-jdbc-wrapper/issues)
