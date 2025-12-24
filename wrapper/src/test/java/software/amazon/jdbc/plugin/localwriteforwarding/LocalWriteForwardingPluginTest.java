/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.jdbc.plugin.localwriteforwarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import software.amazon.jdbc.PluginService;

/**
 * Unit tests for {@link LocalWriteForwardingPlugin}.
 */
class LocalWriteForwardingPluginTest {

  @Test
  void testIsDDLStatement_CreateTable() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("CREATE TABLE users (id INT)"));
    assertTrue(plugin.isDDLStatement("create table test (name VARCHAR)"));
    assertTrue(plugin.isDDLStatement("  CREATE TABLE test (id INT)"));
  }

  @Test
  void testIsDDLStatement_AlterTable() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("ALTER TABLE users ADD COLUMN email VARCHAR"));
    assertTrue(plugin.isDDLStatement("alter table test drop column name"));
  }

  @Test
  void testIsDDLStatement_DropTable() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("DROP TABLE users"));
    assertTrue(plugin.isDDLStatement("drop table if exists test"));
  }

  @Test
  void testIsDDLStatement_Truncate() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("TRUNCATE TABLE users"));
    assertTrue(plugin.isDDLStatement("truncate test"));
  }

  @Test
  void testIsDDLStatement_Grant() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("GRANT SELECT ON users TO username"));
    assertTrue(plugin.isDDLStatement("REVOKE INSERT ON test FROM user"));
  }

  @Test
  void testIsDDLStatement_Vacuum() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    assertTrue(plugin.isDDLStatement("VACUUM users"));
    assertTrue(plugin.isDDLStatement("ANALYZE test"));
  }

  @Test
  void testIsDDLStatement_NotDDL() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    // DML statements should return false
    assertFalse(plugin.isDDLStatement("SELECT * FROM users"));
    assertFalse(plugin.isDDLStatement("INSERT INTO users VALUES (1, 'test')"));
    assertFalse(plugin.isDDLStatement("UPDATE users SET name = 'test'"));
    assertFalse(plugin.isDDLStatement("DELETE FROM users WHERE id = 1"));
    
    // Empty or null
    assertFalse(plugin.isDDLStatement(""));
    assertFalse(plugin.isDDLStatement("   "));
    assertFalse(plugin.isDDLStatement(null));
    
    // Comments
    assertFalse(plugin.isDDLStatement("-- CREATE TABLE"));
    assertFalse(plugin.isDDLStatement("/* CREATE TABLE */"));
  }

  @Test
  void testIsDDLStatement_SelectIntoNotSupported() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);

    // Note: This is a limitation - we can't detect SELECT INTO without full SQL parsing
    // For now, we only detect DDL keywords at the start
    assertFalse(plugin.isDDLStatement("SELECT * INTO new_table FROM users"));
  }

  @Test
  void testConsistencyModeConfiguration() {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    
    // Test default
    props.setProperty("localWriteForwardingConsistencyMode", "SESSION");
    LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);
    // Can't easily test private field, but verifies no exception
    
    // Test EVENTUAL
    props.setProperty("localWriteForwardingConsistencyMode", "EVENTUAL");
    plugin = new LocalWriteForwardingPlugin(mockService, props);
    
    // Test GLOBAL
    props.setProperty("localWriteForwardingConsistencyMode", "GLOBAL");
    plugin = new LocalWriteForwardingPlugin(mockService, props);
    
    // Test OFF
    props.setProperty("localWriteForwardingConsistencyMode", "OFF");
    plugin = new LocalWriteForwardingPlugin(mockService, props);
  }

  @Test
  void testInvalidConsistencyModeRejected() throws SQLException {
    final PluginService mockService = mock(PluginService.class);
    final Properties props = new Properties();
    final LocalWriteForwardingPlugin plugin = new LocalWriteForwardingPlugin(mockService, props);
    
    // Create a mock connection
    final Connection mockConnection = mock(Connection.class);
    
    // Test that invalid mode is rejected
    try {
      plugin.setConsistencyMode(mockConnection, "INVALID_MODE");
      fail("Should have thrown IllegalArgumentException for invalid consistency mode");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("Invalid consistency mode"));
    }
    
    // Test SQL injection attempt is rejected
    try {
      plugin.setConsistencyMode(mockConnection, "SESSION'; DROP TABLE users; --");
      fail("Should have thrown IllegalArgumentException for SQL injection attempt");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("Invalid consistency mode"));
    }
  }
}
