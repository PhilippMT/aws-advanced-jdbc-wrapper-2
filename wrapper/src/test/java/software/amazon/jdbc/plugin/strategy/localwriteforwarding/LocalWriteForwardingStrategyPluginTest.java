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

package software.amazon.jdbc.plugin.strategy.localwriteforwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.jdbc.HostRole;
import software.amazon.jdbc.HostSpec;
import software.amazon.jdbc.HostSpecBuilder;
import software.amazon.jdbc.PluginService;
import software.amazon.jdbc.hostavailability.SimpleHostAvailabilityStrategy;
import software.amazon.jdbc.plugin.strategy.fastestresponse.HostResponseTimeService;

public class LocalWriteForwardingStrategyPluginTest {

  @Mock
  private PluginService mockPluginService;

  @Mock
  private HostResponseTimeService mockHostResponseTimeService;

  private Properties properties;

  private HostSpec writerInAz1;
  private HostSpec readerInAz1;
  private HostSpec readerInAz2;
  private HostSpec readerInAz3;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    writerInAz1 = new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
        .host("writer-1.cluster-xxx.us-east-1.rds.amazonaws.com")
        .hostId("writer-1")
        .port(5432)
        .role(HostRole.WRITER)
        .build();

    readerInAz1 = new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
        .host("reader-1.cluster-xxx.us-east-1.rds.amazonaws.com")
        .hostId("reader-1")
        .port(5432)
        .role(HostRole.READER)
        .build();

    readerInAz2 = new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
        .host("reader-2.cluster-xxx.us-east-1.rds.amazonaws.com")
        .hostId("reader-2")
        .port(5432)
        .role(HostRole.READER)
        .build();

    readerInAz3 = new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
        .host("reader-3.cluster-xxx.us-east-1.rds.amazonaws.com")
        .hostId("reader-3")
        .port(5432)
        .role(HostRole.READER)
        .build();

    properties = new Properties();
    properties.setProperty("localWriteForwardingApplicationAz", "us-east-1a");
    properties.setProperty("localWriteForwardingHostToAzMapping",
        "writer-1:us-east-1a,reader-1:us-east-1a,reader-2:us-east-1b,reader-3:us-east-1c");
  }

  private LocalWriteForwardingStrategyPlugin createPlugin() {
    return new LocalWriteForwardingStrategyPlugin(
        mockPluginService, properties, mockHostResponseTimeService);
  }

  @Test
  void testAcceptsStrategy() {
    LocalWriteForwardingStrategyPlugin plugin = createPlugin();

    assertTrue(plugin.acceptsStrategy(HostRole.READER, "localWriteForwarding"));
    assertTrue(plugin.acceptsStrategy(HostRole.WRITER, "localWriteForwarding"));
    assertTrue(plugin.acceptsStrategy(null, "localWriteForwarding"));
    assertFalse(plugin.acceptsStrategy(HostRole.READER, "fastestResponse"));
    assertFalse(plugin.acceptsStrategy(HostRole.READER, "random"));
  }

  @Test
  void testSelectsSameAzReaderForWriteRole() throws SQLException {
    // With local write forwarding, even when a WRITER role is requested,
    // the plugin should select a same-AZ reader instance.
    List<HostSpec> hosts = Arrays.asList(readerInAz1, readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(any())).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.WRITER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("reader-1", selected.getHostId());
    assertEquals(HostRole.READER, selected.getRole());
  }

  @Test
  void testSelectsSameAzWriterForReadRole() throws SQLException {
    // Even for a READ role, the plugin prefers the same-AZ instance.
    // Only writer-1 is in us-east-1a.
    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(any())).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("writer-1", selected.getHostId());
  }

  @Test
  void testSelectsFastestSameAzHostWhenMultipleInSameAz() throws SQLException {
    // Both writer-1 and reader-1 are in us-east-1a; reader-1 has faster response.
    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz1, readerInAz2);

    when(mockHostResponseTimeService.getResponseTime(writerInAz1)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz1)).thenReturn(50);
    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.WRITER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("reader-1", selected.getHostId());
  }

  @Test
  void testFallbackToFastestResponseWhenNoSameAzHost() throws SQLException {
    // Application AZ is us-east-1a, but no host is in that AZ.
    properties.setProperty("localWriteForwardingHostToAzMapping",
        "reader-2:us-east-1b,reader-3:us-east-1c");

    List<HostSpec> hosts = Arrays.asList(readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz3)).thenReturn(50);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("reader-3", selected.getHostId());
  }

  @Test
  void testFallbackRespectsRoleWhenNoSameAzHost() throws SQLException {
    // Fallback to fastest response respects the role filter.
    properties.setProperty("localWriteForwardingApplicationAz", "us-east-1d");
    properties.setProperty("localWriteForwardingHostToAzMapping",
        "writer-1:us-east-1a,reader-2:us-east-1b,reader-3:us-east-1c");

    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(writerInAz1)).thenReturn(10);
    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz3)).thenReturn(50);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    // Should select reader-3 (fastest reader), not writer-1 (fastest overall)
    assertNotNull(selected);
    assertEquals("reader-3", selected.getHostId());
  }

  @Test
  void testFallbackWithNullRoleConsidersAllHosts() throws SQLException {
    // Null role = consider all hosts.
    properties.setProperty("localWriteForwardingApplicationAz", "us-east-1d");
    properties.setProperty("localWriteForwardingHostToAzMapping",
        "writer-1:us-east-1a,reader-2:us-east-1b,reader-3:us-east-1c");

    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(writerInAz1)).thenReturn(10);
    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz3)).thenReturn(50);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, null, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("writer-1", selected.getHostId());
  }

  @Test
  void testReturnsNullForUnsupportedStrategy() throws SQLException {
    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz1);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "randomStrategy");

    assertNull(selected);
  }

  @Test
  void testReturnsNullWhenNoHostsAvailable() throws SQLException {
    List<HostSpec> hosts = Collections.emptyList();

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    assertNull(selected);
  }

  @Test
  void testParseHostAzMapping_validMapping() {
    Map<String, String> mapping = LocalWriteForwardingStrategyPlugin.parseHostAzMapping(
        "instance-1:us-east-1a,instance-2:us-east-1b,instance-3:us-east-1c");

    assertEquals(3, mapping.size());
    assertEquals("us-east-1a", mapping.get("instance-1"));
    assertEquals("us-east-1b", mapping.get("instance-2"));
    assertEquals("us-east-1c", mapping.get("instance-3"));
  }

  @Test
  void testParseHostAzMapping_withSpaces() {
    Map<String, String> mapping = LocalWriteForwardingStrategyPlugin.parseHostAzMapping(
        " instance-1 : us-east-1a , instance-2 : us-east-1b ");

    assertEquals(2, mapping.size());
    assertEquals("us-east-1a", mapping.get("instance-1"));
    assertEquals("us-east-1b", mapping.get("instance-2"));
  }

  @Test
  void testParseHostAzMapping_nullInput() {
    Map<String, String> mapping = LocalWriteForwardingStrategyPlugin.parseHostAzMapping(null);
    assertTrue(mapping.isEmpty());
  }

  @Test
  void testParseHostAzMapping_emptyInput() {
    Map<String, String> mapping = LocalWriteForwardingStrategyPlugin.parseHostAzMapping("");
    assertTrue(mapping.isEmpty());
  }

  @Test
  void testParseHostAzMapping_invalidEntries() {
    Map<String, String> mapping = LocalWriteForwardingStrategyPlugin.parseHostAzMapping(
        "invalid,instance-1:us-east-1a,:us-east-1b,instance-2:");

    assertEquals(1, mapping.size());
    assertEquals("us-east-1a", mapping.get("instance-1"));
  }

  @Test
  void testNoApplicationAzFallsBackToFastestResponse() throws SQLException {
    // No AZ configured - should fall back to fastest response.
    properties.remove("localWriteForwardingApplicationAz");

    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz1, readerInAz2);

    when(mockHostResponseTimeService.getResponseTime(writerInAz1)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz1)).thenReturn(50);
    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("reader-1", selected.getHostId());
  }

  @Test
  void testNoMappingFallsBackToFastestResponse() throws SQLException {
    // No host-to-AZ mapping configured - should fall back to fastest response.
    properties.remove("localWriteForwardingHostToAzMapping");

    List<HostSpec> hosts = Arrays.asList(writerInAz1, readerInAz1, readerInAz2);

    when(mockHostResponseTimeService.getResponseTime(writerInAz1)).thenReturn(200);
    when(mockHostResponseTimeService.getResponseTime(readerInAz1)).thenReturn(50);
    when(mockHostResponseTimeService.getResponseTime(readerInAz2)).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();
    HostSpec selected = plugin.getHostSpecByStrategy(hosts, HostRole.READER, "localWriteForwarding");

    assertNotNull(selected);
    assertEquals("reader-1", selected.getHostId());
  }

  @Test
  void testSameAzHostSelectedConsistentlyRegardlessOfRequestedRole() throws SQLException {
    // Verify that the same host is selected for both READER and WRITER roles,
    // confirming role-agnostic same-AZ selection with local write forwarding.
    List<HostSpec> hosts = Arrays.asList(readerInAz1, readerInAz2, readerInAz3);

    when(mockHostResponseTimeService.getResponseTime(any())).thenReturn(100);
    when(mockPluginService.getHosts()).thenReturn(hosts);

    LocalWriteForwardingStrategyPlugin plugin = createPlugin();

    // For WRITER role, should still pick the same-AZ reader
    HostSpec writerSelection = plugin.getHostSpecByStrategy(
        hosts, HostRole.WRITER, "localWriteForwarding");
    assertNotNull(writerSelection);
    assertEquals("reader-1", writerSelection.getHostId());

    // For READER role, should also pick the same-AZ reader
    HostSpec readerSelection = plugin.getHostSpecByStrategy(
        hosts, HostRole.READER, "localWriteForwarding");
    assertNotNull(readerSelection);
    assertEquals("reader-1", readerSelection.getHostId());
  }
}
