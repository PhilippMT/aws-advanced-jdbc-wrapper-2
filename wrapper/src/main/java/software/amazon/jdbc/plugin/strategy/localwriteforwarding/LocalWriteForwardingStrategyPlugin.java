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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import software.amazon.jdbc.AwsWrapperProperty;
import software.amazon.jdbc.HostRole;
import software.amazon.jdbc.HostSpec;
import software.amazon.jdbc.JdbcCallable;
import software.amazon.jdbc.NodeChangeOptions;
import software.amazon.jdbc.PluginService;
import software.amazon.jdbc.PropertyDefinition;
import software.amazon.jdbc.plugin.AbstractConnectionPlugin;
import software.amazon.jdbc.plugin.strategy.fastestresponse.HostResponseTimeService;
import software.amazon.jdbc.plugin.strategy.fastestresponse.HostResponseTimeServiceImpl;
import software.amazon.jdbc.util.FullServicesContainer;
import software.amazon.jdbc.util.StringUtils;

/**
 * A strategy plugin for Aurora PostgreSQL clusters with local write forwarding enabled.
 *
 * <p>With local write forwarding, reader instances can also handle write operations.
 * This plugin selects the DB instance in the same availability zone (AZ) as the application,
 * regardless of its role (reader or writer), for both read and write transactions.
 *
 * <p>If no instance in the same AZ is available, the plugin falls back to selecting the
 * instance with the fastest response time.
 *
 * <p>Note: Certain SQL statements are not supported with local write forwarding including
 * DDL statements, ANALYZE, CLUSTER, COPY, cursors, GRANT/REVOKE, LISTEN/NOTIFY, LOCK,
 * SAVEPOINT, SELECT INTO, SET CONSTRAINTS, sequence updates (nextval/setval), TRUNCATE,
 * two-phase commit commands, user-defined functions/procedures, and VACUUM. These limitations
 * are documented at:
 * https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-postgresql-write-forwarding-limitations.html
 */
public class LocalWriteForwardingStrategyPlugin extends AbstractConnectionPlugin {

  private static final Logger LOGGER =
      Logger.getLogger(LocalWriteForwardingStrategyPlugin.class.getName());

  public static final String LOCAL_WRITE_FORWARDING_STRATEGY_NAME = "localWriteForwarding";

  private static final Set<String> subscribedMethods =
      Collections.unmodifiableSet(new HashSet<String>() {
        {
          add("connect");
          add("forceConnect");
          add("notifyNodeListChanged");
          add("acceptsStrategy");
          add("getHostSpecByStrategy");
        }
      });

  public static final AwsWrapperProperty APPLICATION_AZ =
      new AwsWrapperProperty(
          "localWriteForwardingApplicationAz",
          null,
          "The availability zone where the application is running (e.g. 'us-east-1a').");

  public static final AwsWrapperProperty HOST_AZ_MAPPING =
      new AwsWrapperProperty(
          "localWriteForwardingHostToAzMapping",
          null,
          "A comma-separated mapping of DB instance host IDs to availability zones "
              + "(e.g. 'instance-1:us-east-1a,instance-2:us-east-1b').");

  public static final AwsWrapperProperty RESPONSE_MEASUREMENT_INTERVAL_MILLIS =
      new AwsWrapperProperty(
          "localWriteForwardingResponseMeasurementIntervalMs",
          "30000",
          "Interval in millis between measuring response time to a database node for fallback selection.");

  protected final @NonNull PluginService pluginService;
  protected final @NonNull Properties properties;
  protected final @NonNull HostResponseTimeService hostResponseTimeService;

  protected List<HostSpec> hosts;
  protected Map<String, String> hostIdToAz;
  protected String applicationAz;

  static {
    PropertyDefinition.registerPluginProperties(LocalWriteForwardingStrategyPlugin.class);
    PropertyDefinition.registerPluginProperties("lwf-");
  }

  public LocalWriteForwardingStrategyPlugin(
      final FullServicesContainer servicesContainer,
      final @NonNull Properties properties) {
    this(servicesContainer.getPluginService(),
        properties,
        new HostResponseTimeServiceImpl(
            servicesContainer,
            properties,
            RESPONSE_MEASUREMENT_INTERVAL_MILLIS.getInteger(properties)));
  }

  public LocalWriteForwardingStrategyPlugin(
      final @NonNull PluginService pluginService,
      final @NonNull Properties properties,
      final @NonNull HostResponseTimeService hostResponseTimeService) {

    this.pluginService = pluginService;
    this.properties = properties;
    this.hostResponseTimeService = hostResponseTimeService;
    this.applicationAz = APPLICATION_AZ.getString(properties);
    this.hostIdToAz = parseHostAzMapping(HOST_AZ_MAPPING.getString(properties));
  }

  @Override
  public Set<String> getSubscribedMethods() {
    return subscribedMethods;
  }

  @Override
  public Connection connect(
      final String driverProtocol,
      final HostSpec hostSpec,
      final Properties props,
      final boolean isInitialConnection,
      final JdbcCallable<Connection, SQLException> connectFunc)
      throws SQLException {

    Connection conn = connectFunc.call();
    if (isInitialConnection) {
      this.hosts = this.pluginService.getHosts();
      this.hostResponseTimeService.setHosts(this.hosts);
    }
    return conn;
  }

  @Override
  public boolean acceptsStrategy(@Nullable HostRole role, String strategy) {
    return LOCAL_WRITE_FORWARDING_STRATEGY_NAME.equalsIgnoreCase(strategy);
  }

  @Override
  public HostSpec getHostSpecByStrategy(final @Nullable HostRole role, final String strategy)
      throws SQLException, UnsupportedOperationException {
    return this.getHostSpecByStrategy(this.pluginService.getHosts(), role, strategy);
  }

  @Override
  public HostSpec getHostSpecByStrategy(
      final List<HostSpec> hosts, final @Nullable HostRole role, final String strategy)
      throws SQLException, UnsupportedOperationException {

    if (!acceptsStrategy(role, strategy)) {
      return null;
    }

    // With local write forwarding, reader instances can handle writes too.
    // Try to find a host in the same AZ as the application, regardless of role.
    final HostSpec sameAzHost = selectSameAzHost(hosts);
    if (sameAzHost != null) {
      LOGGER.fine(() -> String.format(
          "Selected same-AZ host: %s (hostId=%s, role=%s, az=%s)",
          sameAzHost.getHost(), sameAzHost.getHostId(), sameAzHost.getRole(),
          this.applicationAz));
      return sameAzHost;
    }

    // Fallback: select the host with the fastest response time, respecting role.
    LOGGER.fine(() -> String.format(
        "No host found in AZ '%s'. Falling back to fastest response time selection.",
        this.applicationAz));
    return selectFastestResponseHost(hosts, role);
  }

  @Override
  public void notifyNodeListChanged(final Map<String, EnumSet<NodeChangeOptions>> changes) {
    this.hosts = this.pluginService.getHosts();
    this.hostResponseTimeService.setHosts(this.hosts);
  }

  /**
   * Select a host in the same AZ as the application.
   * With local write forwarding, role is ignored since readers can also process writes.
   * If multiple hosts exist in the same AZ, the one with the fastest response time is selected.
   */
  protected @Nullable HostSpec selectSameAzHost(final List<HostSpec> hosts) {
    if (StringUtils.isNullOrEmpty(this.applicationAz) || this.hostIdToAz.isEmpty()) {
      return null;
    }

    return hosts.stream()
        .filter(h -> this.applicationAz.equalsIgnoreCase(
            this.hostIdToAz.get(h.getHostId())))
        .map(h -> new ResponseTimeTuple(h, this.hostResponseTimeService.getResponseTime(h)))
        .sorted(Comparator.comparingInt(t -> t.responseTime))
        .map(t -> t.hostSpec)
        .findFirst()
        .orElse(null);
  }

  /**
   * Select the host with the fastest response time, optionally filtered by role.
   */
  protected @Nullable HostSpec selectFastestResponseHost(
      final List<HostSpec> hosts, final @Nullable HostRole role) {

    return hosts.stream()
        .filter(h -> role == null || role.equals(h.getRole()))
        .map(h -> new ResponseTimeTuple(h, this.hostResponseTimeService.getResponseTime(h)))
        .sorted(Comparator.comparingInt(t -> t.responseTime))
        .map(t -> t.hostSpec)
        .findFirst()
        .orElse(null);
  }

  /**
   * Parse the host-to-AZ mapping string.
   * Expected format: "hostId1:az1,hostId2:az2"
   */
  static Map<String, String> parseHostAzMapping(final @Nullable String mapping) {
    final Map<String, String> result = new HashMap<>();
    if (StringUtils.isNullOrEmpty(mapping)) {
      return result;
    }

    for (final String entry : mapping.split(",")) {
      final String trimmed = entry.trim();
      final int separatorIdx = trimmed.indexOf(':');
      if (separatorIdx > 0 && separatorIdx < trimmed.length() - 1) {
        final String hostId = trimmed.substring(0, separatorIdx).trim();
        final String az = trimmed.substring(separatorIdx + 1).trim();
        if (!hostId.isEmpty() && !az.isEmpty()) {
          result.put(hostId, az);
        }
      }
    }

    return result;
  }

  private static class ResponseTimeTuple {
    public final HostSpec hostSpec;
    public final int responseTime;

    public ResponseTimeTuple(final HostSpec hostSpec, int responseTime) {
      this.hostSpec = hostSpec;
      this.responseTime = responseTime;
    }
  }
}
