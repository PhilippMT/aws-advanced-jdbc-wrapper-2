# Local Write Forwarding Support - Design Document

## Overview

This document describes the design for implementing local write forwarding support for Aurora PostgreSQL 17+ clusters in the AWS Advanced JDBC Wrapper.

## Background

### What is Local Write Forwarding?

Local write forwarding is a feature in Aurora PostgreSQL (available in versions 14.13+, 15.8+, 16.4+, and 17+) that allows read replicas to accept write requests. These writes are automatically forwarded to the writer instance for commitment.

**Benefits:**
- Simplified application architecture - single connection endpoint
- Read-after-write consistency without complex application logic
- Improved read scaling for workloads requiring consistency

**Key Characteristics:**
- Enabled at cluster level via `EnableLocalWriteForwarding` parameter
- Controlled at session level via `apg_write_forward.consistency_mode` parameter
- Consistency modes: SESSION (default), EVENTUAL, GLOBAL, OFF
- Not compatible with RDS Proxy

## Limitations and Considerations

### Unsupported SQL Statements with Write Forwarding

The following statements **CANNOT** be used with write forwarding and must be executed on the writer instance:
- DDL statements (CREATE, ALTER, DROP, etc.)
- ANALYZE
- CLUSTER
- COPY
- Cursors
- GRANT/REVOKE/REASSIGN OWNED/SECURITY LABEL
- LISTEN/NOTIFY
- LOCK
- SAVEPOINT
- SELECT INTO
- SET CONSTRAINTS
- Sequence updates (nextval(), setval())
- TRUNCATE
- Two-phase commit commands
- User-defined functions and procedures
- VACUUM

### Supported Statements

- DML statements (INSERT, UPDATE, DELETE)
- SELECT queries
- EXPLAIN statements
- PREPARE and EXECUTE statements
- SELECT FOR UPDATE/SHARE

### Transaction Isolation

- Supports: REPEATABLE READ and READ COMMITTED
- **Does NOT support**: SERIALIZABLE isolation level

## Architecture Decision Tree

```
┌─────────────────────────────────────┐
│ Application requests connection     │
└─────────────┬───────────────────────┘
              │
              ▼
┌─────────────────────────────────────┐
│ Is LocalWriteForwardingStatus       │
│ = "enabled" for cluster?            │
└─────────────┬───────────────────────┘
              │
      ┌───────┴────────┐
      │                │
      NO              YES
      │                │
      │                ▼
      │    ┌──────────────────────────┐
      │    │ Is statement type DDL?   │
      │    └──────────┬───────────────┘
      │               │
      │       ┌───────┴────────┐
      │       │                │
      │      YES              NO
      │       │                │
      │       ▼                ▼
      │  ┌────────┐    ┌─────────────┐
      │  │WRITER  │    │Look for      │
      │  │instance│    │reader in same│
      │  └────────┘    │AZ as client  │
      │                └──────┬───────┘
      │                       │
      │               ┌───────┴────────┐
      │               │                │
      │           FOUND           NOT FOUND
      │               │                │
      │               ▼                ▼
      │         ┌──────────┐    ┌───────────┐
      │         │Use same  │    │Use fastest│
      │         │AZ reader │    │response   │
      │         └──────────┘    │strategy   │
      │                         └───────────┘
      │
      ▼
┌──────────────────────┐
│Use standard          │
│ReadWriteSplitting    │
│plugin behavior       │
└──────────────────────┘
```

## Implementation Design

### 1. Core Components

#### 1.1 LocalWriteForwardingPlugin

A new plugin extending `AbstractConnectionPlugin` that:
- Detects cluster LocalWriteForwardingStatus
- Routes connections based on statement type and availability zone
- Manages session-level consistency mode parameter
- Integrates with SessionStateService for failover support

**Key Methods:**
```java
public class LocalWriteForwardingPlugin extends AbstractConnectionPlugin {
  
  // Detect if cluster has local write forwarding enabled
  private boolean detectLocalWriteForwardingStatus(Connection conn);
  
  // Get availability zone for an instance
  private String getInstanceAvailabilityZone(HostSpec host);
  
  // Determine if SQL statement is DDL
  private boolean isDDLStatement(String sql);
  
  // Select appropriate reader based on AZ proximity
  private HostSpec selectReaderByAvailabilityZone(List<HostSpec> readers, String clientAZ);
  
  // Set consistency mode on connection
  private void setConsistencyMode(Connection conn, String mode);
}
```

#### 1.2 SessionStateService Extension

Extend `SessionStateService` and `SessionStateServiceImpl` to manage the `apg_write_forward.consistency_mode` parameter:

```java
public interface SessionStateService {
  // Existing methods...
  
  // Local write forwarding consistency mode
  Optional<String> getWriteForwardingConsistencyMode() throws SQLException;
  void setWriteForwardingConsistencyMode(String mode) throws SQLException;
  void setupPristineWriteForwardingConsistencyMode() throws SQLException;
  void setupPristineWriteForwardingConsistencyMode(String mode) throws SQLException;
}
```

#### 1.3 HostSpec Extension

Add availability zone tracking to `HostSpec`:

```java
public class HostSpec {
  // Existing fields...
  
  protected String availabilityZone;
  
  public String getAvailabilityZone() {
    return this.availabilityZone;
  }
  
  public void setAvailabilityZone(String az) {
    this.availabilityZone = az;
  }
}
```

#### 1.4 Topology Query Enhancement

Update Aurora topology queries to fetch availability zone information:

```sql
-- Enhanced topology query to include AZ
SELECT 
  server_id,
  CASE WHEN session_id = 'MASTER_SESSION_ID' THEN 1 ELSE 0 END AS is_writer,
  cpu,
  lag,
  last_update_timestamp,
  availability_zone  -- NEW FIELD
FROM aurora_replica_status()
ORDER BY last_update_timestamp;
```

### 2. Configuration Properties

```java
public static final AwsWrapperProperty ENABLE_LOCAL_WRITE_FORWARDING =
    new AwsWrapperProperty(
        "enableLocalWriteForwarding",
        "false",
        "Enable local write forwarding support for Aurora PostgreSQL 17+ clusters.");

public static final AwsWrapperProperty LOCAL_WRITE_FORWARDING_CONSISTENCY_MODE =
    new AwsWrapperProperty(
        "localWriteForwardingConsistencyMode",
        "SESSION",
        "Consistency mode for local write forwarding: SESSION, EVENTUAL, GLOBAL, or OFF.",
        false,
        new String[] {"SESSION", "EVENTUAL", "GLOBAL", "OFF"});

public static final AwsWrapperProperty ENABLE_DDL_DETECTION =
    new AwsWrapperProperty(
        "enableDDLDetection",
        "true",
        "Enable automatic detection of DDL statements to route to writer instance.");
```

### 3. DDL Statement Detection

Implement a SQL parser utility to detect DDL statements:

```java
public class SqlStatementClassifier {
  
  private static final Set<String> DDL_KEYWORDS = Set.of(
      "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
      "GRANT", "REVOKE", "ANALYZE", "CLUSTER", "VACUUM",
      "LOCK", "SAVEPOINT", "LISTEN", "NOTIFY"
  );
  
  public static boolean isDDL(String sql) {
    if (sql == null || sql.trim().isEmpty()) {
      return false;
    }
    
    String normalized = sql.trim().toUpperCase();
    
    // Check for DDL keywords at statement start
    for (String keyword : DDL_KEYWORDS) {
      if (normalized.startsWith(keyword + " ")) {
        return true;
      }
    }
    
    return false;
  }
}
```

### 4. Cluster Status Detection

Implement cluster metadata detection to check LocalWriteForwardingStatus:

```java
public class LocalWriteForwardingClusterStatus {
  
  public enum Status {
    ENABLED,
    DISABLED,
    ENABLING,
    DISABLING,
    REQUESTED,
    NOT_AVAILABLE
  }
  
  // Cache cluster status to avoid repeated AWS API calls
  private static final Map<String, CachedStatus> clusterStatusCache = 
      new ConcurrentHashMap<>();
  
  public static Status detectClusterStatus(Connection conn, String clusterIdentifier) {
    // Check cache first
    CachedStatus cached = clusterStatusCache.get(clusterIdentifier);
    if (cached != null && !cached.isExpired()) {
      return cached.getStatus();
    }
    
    // Query cluster parameter or use AWS RDS API
    // Implementation depends on available AWS SDK integration
    Status status = queryClusterStatus(conn, clusterIdentifier);
    
    clusterStatusCache.put(clusterIdentifier, 
        new CachedStatus(status, System.currentTimeMillis()));
    
    return status;
  }
  
  private static Status queryClusterStatus(Connection conn, String clusterIdentifier) {
    // Option 1: Query pg_settings for apg_write_forward parameters
    // Option 2: Use AWS RDS DescribeDBClusters API
    // Implementation TBD based on authentication and permissions
    return Status.NOT_AVAILABLE;
  }
}
```

### 5. Availability Zone Detection

Implement AZ detection for routing decisions:

```java
public class AvailabilityZoneDetector {
  
  // Get AZ for the client/application
  public static String getClientAvailabilityZone() {
    // Option 1: Use AWS EC2 instance metadata service
    // Option 2: Configuration property
    // Option 3: AWS SDK EC2 DescribeInstances
    return System.getenv("AWS_AVAILABILITY_ZONE");
  }
  
  // Get AZ for a database instance
  public static String getInstanceAvailabilityZone(HostSpec host) {
    // Parse from RDS endpoint pattern or query AWS RDS API
    // Example: instance.cluster-xyz.us-east-1a.rds.amazonaws.com
    String hostname = host.getHost();
    // Extract AZ from hostname or query AWS API
    return extractAZFromHostname(hostname);
  }
}
```

### 6. Connection Routing Logic

Main routing logic in the plugin:

```java
public class LocalWriteForwardingPlugin extends AbstractConnectionPlugin {
  
  private Connection selectConnection(String sql, boolean inTransaction) throws SQLException {
    // Step 1: Check if local write forwarding is enabled
    if (!isLocalWriteForwardingEnabled()) {
      return getStandardConnection(sql);
    }
    
    // Step 2: Check if statement is DDL
    if (SqlStatementClassifier.isDDL(sql)) {
      return getWriterConnection();
    }
    
    // Step 3: Check if we're in a transaction that started with DDL
    if (inTransaction && transactionStartedWithDDL) {
      return getWriterConnection();
    }
    
    // Step 4: Select reader by availability zone
    String clientAZ = AvailabilityZoneDetector.getClientAvailabilityZone();
    List<HostSpec> readers = getAvailableReaders();
    
    HostSpec selectedReader = selectReaderByAZ(readers, clientAZ);
    if (selectedReader != null) {
      return getConnection(selectedReader);
    }
    
    // Step 5: Fallback to fastest response strategy
    return getConnectionByFastestResponse(readers);
  }
  
  private HostSpec selectReaderByAZ(List<HostSpec> readers, String clientAZ) {
    if (clientAZ == null || readers.isEmpty()) {
      return null;
    }
    
    // Find reader in same AZ
    for (HostSpec reader : readers) {
      if (clientAZ.equals(reader.getAvailabilityZone())) {
        return reader;
      }
    }
    
    return null;
  }
}
```

### 7. Failover Support

On failover, restore session state including consistency mode:

```java
@Override
public void onFailoverComplete() throws SQLException {
  // Get new connection after failover
  Connection newConn = pluginService.getCurrentConnection();
  
  // Restore session state including consistency mode
  sessionStateService.applyCurrentSessionState(newConn);
  
  // Specifically restore write forwarding mode
  Optional<String> mode = sessionStateService.getWriteForwardingConsistencyMode();
  if (mode.isPresent()) {
    setConsistencyMode(newConn, mode.get());
  }
}

private void setConsistencyMode(Connection conn, String mode) throws SQLException {
  try (Statement stmt = conn.createStatement()) {
    stmt.execute("SET apg_write_forward.consistency_mode = '" + mode + "'");
  }
}
```

### 8. Connection Pool Integration

When returning connections to the pool, reset consistency mode:

```java
@Override
public void releaseResources() {
  try {
    Connection conn = pluginService.getCurrentConnection();
    if (conn != null && !conn.isClosed()) {
      // Reset to default consistency mode before returning to pool
      setConsistencyMode(conn, "SESSION");
    }
  } catch (SQLException e) {
    LOGGER.warning("Failed to reset consistency mode: " + e.getMessage());
  }
}
```

## Integration with Example Application

### Modified application.yml

```yaml
spring:
  datasource:
    load-balanced-writer-and-reader-datasource:
      url: jdbc:aws-wrapper:postgresql://test-cluster.cluster-XYZ.us-east-2.rds.amazonaws.com:5432/postgres?wrapperProfileName=LWF&enableLocalWriteForwarding=true&localWriteForwardingConsistencyMode=SESSION&readerHostSelectorStrategy=roundRobin
      username: dev_user
      password: dev_password
      driver-class-name: software.amazon.jdbc.Driver
      type: org.springframework.jdbc.datasource.SimpleDriverDataSource
```

### Configuration Profile

Create a new configuration profile preset for local write forwarding:

```java
public static final String LWF = "LWF"; // Local Write Forwarding profile

// In DriverConfigurationProfiles.java
ConfigurationProfileBuilder.get()
    .withName("LWF")
    .withPlugins("localWriteForwarding,readWriteSplitting,failover2,efm2,hostMonitoring")
    .withConnectionProvider(new HikariPooledConnectionProvider())
    .withProperty(LocalWriteForwardingPlugin.ENABLE_LOCAL_WRITE_FORWARDING, "true")
    .withProperty(LocalWriteForwardingPlugin.ENABLE_DDL_DETECTION, "true")
    .withProperty(LocalWriteForwardingPlugin.LOCAL_WRITE_FORWARDING_CONSISTENCY_MODE, "SESSION")
    .buildAndSet();
```

## Testing Strategy

### Unit Tests

1. Test DDL detection logic
2. Test AZ matching logic
3. Test consistency mode setting/restoration
4. Test session state preservation

### Integration Tests

1. Test with actual Aurora PostgreSQL 17+ cluster with LWF enabled
2. Test DDL routing to writer
3. Test DML routing to same-AZ reader
4. Test failover with state restoration
5. Test connection pool integration

### Test Scenarios

```java
@Test
public void testDDLRoutedToWriter() {
  // Execute DDL statement
  connection.execute("CREATE TABLE test (id INT)");
  
  // Verify it was executed on writer instance
  assertTrue(wasExecutedOnWriter());
}

@Test
public void testDMLRoutedToSameAZReader() {
  // Set client AZ
  System.setProperty("AWS_AVAILABILITY_ZONE", "us-east-1a");
  
  // Execute DML statement
  connection.execute("SELECT * FROM users");
  
  // Verify it was executed on reader in us-east-1a
  assertEquals("us-east-1a", getExecutedOnInstanceAZ());
}

@Test
public void testConsistencyModePreservedOnFailover() {
  // Set consistency mode
  connection.execute("SET apg_write_forward.consistency_mode = 'GLOBAL'");
  
  // Trigger failover
  simulateFailover();
  
  // Verify consistency mode is restored
  String mode = getCurrentConsistencyMode();
  assertEquals("GLOBAL", mode);
}
```

## Performance Considerations

1. **Cluster Status Caching**: Cache LocalWriteForwardingStatus to avoid repeated AWS API calls (TTL: 5 minutes)
2. **AZ Detection Caching**: Cache instance AZ information (TTL: 1 hour)
3. **DDL Detection**: Use efficient regex/string matching for DDL detection
4. **Connection Pool**: Reuse connections efficiently with proper state reset

## Security Considerations

1. **rdswriteforwarduser**: Ensure proper permissions for the internal user
2. **Parameter Validation**: Validate consistency mode values to prevent SQL injection
3. **AWS Credentials**: Secure storage of credentials for AWS API calls

## Deployment and Rollout

### Phase 1: Research and Design (Current)
- ✅ Research Aurora PostgreSQL local write forwarding
- ✅ Document capabilities, limitations, and implications
- ✅ Build decision tree
- ✅ Create design document

### Phase 2: Core Implementation
- [ ] Implement LocalWriteForwardingPlugin skeleton
- [ ] Implement DDL detection utility
- [ ] Extend SessionStateService for consistency mode
- [ ] Add HostSpec AZ tracking

### Phase 3: Cluster Detection
- [ ] Implement cluster status detection (AWS RDS API integration)
- [ ] Implement AZ detection for instances
- [ ] Add caching layer for metadata

### Phase 4: Connection Routing
- [ ] Implement routing logic (DDL to writer, DML to same-AZ reader)
- [ ] Integrate with existing ReadWriteSplittingPlugin
- [ ] Implement failover support

### Phase 5: Testing and Validation
- [ ] Unit tests
- [ ] Integration tests with Aurora PostgreSQL 17+
- [ ] Performance testing
- [ ] Update example application

### Phase 6: Documentation and Release
- [ ] User documentation
- [ ] Configuration guide
- [ ] Migration guide from standard read/write splitting
- [ ] Release notes

## Known Limitations

1. **AWS API Dependency**: Requires AWS SDK for RDS API calls to detect cluster status
2. **PostgreSQL Only**: Only supports Aurora PostgreSQL (not MySQL)
3. **Version Requirement**: Requires Aurora PostgreSQL 14.13+, 15.8+, 16.4+, or 17+
4. **No RDS Proxy**: Not compatible with RDS Proxy
5. **DDL Detection**: Simple regex-based DDL detection may have edge cases

## Future Enhancements

1. **Machine Learning for AZ Selection**: Use historical latency data to optimize AZ selection
2. **Advanced DDL Detection**: More sophisticated SQL parsing
3. **Consistency Mode Auto-Tuning**: Automatically adjust based on workload patterns
4. **Multi-Region Support**: Extend to Aurora Global Databases
5. **Monitoring Dashboard**: Real-time visibility into write forwarding metrics

## References

- [Aurora PostgreSQL Local Write Forwarding](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding.html)
- [Configuring Local Write Forwarding](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding-configuring.html)
- [Limitations and Considerations](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding-limitations.html)
- [AWS Advanced JDBC Wrapper Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper)
