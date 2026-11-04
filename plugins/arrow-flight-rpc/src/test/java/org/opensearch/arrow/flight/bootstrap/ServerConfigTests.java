/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.arrow.flight.bootstrap;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ScalingExecutorBuilder;

import java.util.Set;

import static org.opensearch.arrow.flight.bootstrap.ServerConfig.SETTING_FLIGHT_PUBLISH_PORT;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ServerConfigTests extends OpenSearchTestCase {

    private Settings settings;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        settings = Settings.builder()
            .put("arrow.allocation.manager.type", "Netty")
            .put("arrow.enable_null_check_for_get", false)
            .put("arrow.enable_unsafe_memory_access", true)
            .put("arrow.memory.debug.allocator", false)
            .put("flight.ssl.enable", true)
            .put("thread_pool.flight-server.min", 1)
            .put("thread_pool.flight-server.max", 4)
            .put("thread_pool.flight-server.keep_alive", TimeValue.timeValueMinutes(5))
            .build();
    }

    public void testInit() {
        ServerConfig.init(settings);

        // Verify system properties are set correctly
        assertEquals("Netty", System.getProperty("arrow.allocation.manager.type"));
        assertEquals("false", System.getProperty("arrow.enable_null_check_for_get"));
        assertEquals("true", System.getProperty("arrow.enable_unsafe_memory_access"));
        assertEquals("false", System.getProperty("arrow.memory.debug.allocator"));

        // Verify SSL settings
        assertTrue(ServerConfig.isSslEnabled());

        ScalingExecutorBuilder serverExecutorBuilder = ServerConfig.getServerExecutorBuilder();
        ScalingExecutorBuilder flightGrpcExecutorBuilder = ServerConfig.getGrpcExecutorBuilder();

        assertNotNull(serverExecutorBuilder);
        assertNotNull(flightGrpcExecutorBuilder);

        assertEquals(3, serverExecutorBuilder.getRegisteredSettings().size());
        assertEquals(1, serverExecutorBuilder.getRegisteredSettings().get(0).get(settings)); // min
        assertEquals(4, serverExecutorBuilder.getRegisteredSettings().get(1).get(settings)); // max
        assertEquals(TimeValue.timeValueMinutes(5), serverExecutorBuilder.getRegisteredSettings().get(2).get(settings)); // keep alive
    }

    public void testGetSettings() {
        var settings = ServerConfig.getSettings();
        assertNotNull(settings);
        assertFalse(settings.isEmpty());

        assertTrue(settings.contains(ServerConfig.ARROW_ALLOCATION_MANAGER_TYPE));
        assertTrue(settings.contains(ServerConfig.ARROW_ENABLE_NULL_CHECK_FOR_GET));
        assertTrue(settings.contains(ServerConfig.ARROW_ENABLE_UNSAFE_MEMORY_ACCESS));
        assertTrue(settings.contains(ServerConfig.ARROW_ENABLE_DEBUG_ALLOCATOR));
        assertTrue(settings.contains(ServerConfig.ARROW_SSL_ENABLE));
        assertTrue(settings.contains(ServerConfig.FLIGHT_READY_TIMEOUT));
        assertTrue(settings.contains(ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD));
    }

    public void testDefaultSettings() {
        Settings defaultSettings = Settings.EMPTY;
        ServerConfig.init(defaultSettings);

        // Verify default values
        assertEquals(-1, SETTING_FLIGHT_PUBLISH_PORT.get(defaultSettings).intValue());
        assertEquals("Netty", ServerConfig.ARROW_ALLOCATION_MANAGER_TYPE.get(defaultSettings));
        assertFalse(ServerConfig.ARROW_ENABLE_NULL_CHECK_FOR_GET.get(defaultSettings));
        assertTrue(ServerConfig.ARROW_ENABLE_UNSAFE_MEMORY_ACCESS.get(defaultSettings));
        assertFalse(ServerConfig.ARROW_ENABLE_DEBUG_ALLOCATOR.get(defaultSettings));
        assertFalse(ServerConfig.ARROW_SSL_ENABLE.get(defaultSettings));
        assertEquals(TimeValue.timeValueSeconds(60), ServerConfig.FLIGHT_READY_TIMEOUT.get(defaultSettings));
        assertEquals(512L * 1024, ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(defaultSettings).getBytes());
    }

    public void testBackpressureSettingsParse() {
        Settings overridden = Settings.builder()
            .put(ServerConfig.FLIGHT_READY_TIMEOUT.getKey(), TimeValue.timeValueSeconds(5))
            .put(ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.getKey(), new ByteSizeValue(16, ByteSizeUnit.MB))
            .build();
        assertEquals(TimeValue.timeValueSeconds(5), ServerConfig.FLIGHT_READY_TIMEOUT.get(overridden));
        assertEquals(16L * 1024 * 1024, ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(overridden).getBytes());
    }

    /**
     * A peer that vanished without closing its connection is found dead within the node's own ready timeout, so a stream
     * that raised its ready timeout does not keep its producer paused for that peer.
     */
    public void testKeepAliveFindsADeadPeerWithinTheReadyTimeout() {
        TimeValue time = ServerConfig.FLIGHT_KEEPALIVE_TIME.get(Settings.EMPTY);
        TimeValue timeout = ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.get(Settings.EMPTY);
        assertEquals(TimeValue.timeValueSeconds(30), time);
        assertEquals(TimeValue.timeValueSeconds(20), timeout);
        assertThat(time.millis() + timeout.millis(), lessThanOrEqualTo(ServerConfig.FLIGHT_READY_TIMEOUT.get(Settings.EMPTY).millis()));
    }

    /**
     * A node that sets only the timeout, to 30s or more, was valid against the 2h default time before 3.10 and still starts:
     * the time then defaults to 10s more than the timeout.
     */
    public void testKeepAliveTimeoutAloneStillStarts() {
        ClusterSettings clusterSettings = new ClusterSettings(
            Settings.EMPTY,
            Set.of(ServerConfig.FLIGHT_KEEPALIVE_TIME, ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT)
        );
        Settings timeoutOnly = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(60))
            .build();
        clusterSettings.validate(timeoutOnly, true);
        try {
            ServerConfig.init(timeoutOnly);
            assertEquals(TimeValue.timeValueSeconds(70), ServerConfig.getGrpcKeepAliveTime());
            assertEquals(TimeValue.timeValueSeconds(60), ServerConfig.getGrpcKeepAliveTimeout());
        } finally {
            ServerConfig.init(Settings.EMPTY);
        }

        Settings atDefaultTime = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(30))
            .build();
        clusterSettings.validate(atDefaultTime, true);
        assertEquals(TimeValue.timeValueSeconds(40), ServerConfig.FLIGHT_KEEPALIVE_TIME.get(atDefaultTime));

        // A shorter timeout keeps the 30s default rather than pinging more often.
        Settings shorter = Settings.builder().put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(5)).build();
        assertEquals(TimeValue.timeValueSeconds(30), ServerConfig.FLIGHT_KEEPALIVE_TIME.get(shorter));

        // An explicit time is still checked against the timeout.
        Settings explicitTime = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIME.getKey(), TimeValue.timeValueSeconds(30))
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(60))
            .build();
        expectThrows(IllegalArgumentException.class, () -> clusterSettings.validate(explicitTime, true));
    }

    public void testKeepAliveTimeoutMustBeLessThanTime() {
        ClusterSettings clusterSettings = new ClusterSettings(
            Settings.EMPTY,
            Set.of(ServerConfig.FLIGHT_KEEPALIVE_TIME, ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT)
        );
        Settings equal = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIME.getKey(), TimeValue.timeValueSeconds(30))
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(30))
            .build();
        expectThrows(IllegalArgumentException.class, () -> clusterSettings.validate(equal, false));

        Settings greater = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIME.getKey(), TimeValue.timeValueSeconds(30))
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(40))
            .build();
        expectThrows(IllegalArgumentException.class, () -> clusterSettings.validate(greater, false));

        Settings ok = Settings.builder()
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIME.getKey(), TimeValue.timeValueSeconds(60))
            .put(ServerConfig.FLIGHT_KEEPALIVE_TIMEOUT.getKey(), TimeValue.timeValueSeconds(20))
            .build();
        clusterSettings.validate(ok, false);
    }

    public void testReadyTimeoutMinimum() {
        // 100ms minimum is enforced; lower values must be rejected at parse.
        Settings tooLow = Settings.builder().put(ServerConfig.FLIGHT_READY_TIMEOUT.getKey(), TimeValue.timeValueMillis(50)).build();
        expectThrows(IllegalArgumentException.class, () -> ServerConfig.FLIGHT_READY_TIMEOUT.get(tooLow));
    }

    public void testOutboundBufferThresholdBounds() {
        Settings tooLow = Settings.builder()
            .put(ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.getKey(), new ByteSizeValue(64, ByteSizeUnit.KB))
            .build();
        expectThrows(IllegalArgumentException.class, () -> ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(tooLow));

        Settings tooHigh = Settings.builder()
            .put(ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.getKey(), new ByteSizeValue(3, ByteSizeUnit.GB))
            .build();
        expectThrows(IllegalArgumentException.class, () -> ServerConfig.FLIGHT_OUTBOUND_BUFFER_THRESHOLD.get(tooHigh));
    }
}
