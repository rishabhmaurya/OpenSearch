/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.arrow.search;

import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Setting.Property;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.threadpool.ThreadPool;

import java.util.List;

/**
 * Settings of Arrow streaming search, and their current values on this node.
 */
public final class ArrowSearchSettings {

    /**
     * The size record batches grow to. Batches stay below gRPC's default 4MB client message limit, and bound the memory a
     * shard stream holds while the client is not reading.
     */
    public static final Setting<ByteSizeValue> BATCH_SIZE = Setting.byteSizeSetting(
        "search.arrow.batch_size",
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(16, ByteSizeUnit.KB),
        new ByteSizeValue(3, ByteSizeUnit.MB),
        Property.NodeScope,
        Property.Dynamic
    );

    /** The size of the first batch of each shard, which doubles with every batch up to the batch size, so rows arrive early. */
    public static final Setting<ByteSizeValue> FIRST_BATCH_SIZE = Setting.byteSizeSetting(
        "search.arrow.first_batch_size",
        new ByteSizeValue(64, ByteSizeUnit.KB),
        new ByteSizeValue(16, ByteSizeUnit.KB),
        new ByteSizeValue(3, ByteSizeUnit.MB),
        Property.NodeScope,
        Property.Dynamic
    );

    /** The maximum rows in a batch. */
    public static final Setting<Integer> BATCH_ROWS = Setting.intSetting(
        "search.arrow.batch_rows",
        64 * 1024,
        1,
        1024 * 1024,
        Property.NodeScope,
        Property.Dynamic
    );

    /** The largest row a batch may hold, below gRPC's default 4MB client message limit; a larger row fails the search. */
    public static final Setting<ByteSizeValue> MAX_ROW_SIZE = Setting.byteSizeSetting(
        "search.arrow.max_row_size",
        new ByteSizeValue(3584, ByteSizeUnit.KB),
        new ByteSizeValue(16, ByteSizeUnit.KB),
        new ByteSizeValue(64, ByteSizeUnit.MB),
        Property.NodeScope,
        Property.Dynamic
    );

    /**
     * The batch memory one search may hold on its coordinating node, which bounds how many shards it streams at once:
     * each open shard stream holds up to six batches in flight, each as large as the batch size or the maximum row size,
     * whichever is larger.
     */
    public static final Setting<ByteSizeValue> MAX_BUFFERED_BYTES_PER_REQUEST = Setting.byteSizeSetting(
        "search.arrow.max_buffered_bytes_per_request",
        new ByteSizeValue(64, ByteSizeUnit.MB),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(Long.MAX_VALUE),
        Property.NodeScope,
        Property.Dynamic
    );

    /** The batch memory all searches may hold on a coordinating node; a search that does not fit is rejected. */
    public static final Setting<ByteSizeValue> COORDINATOR_MAX_BUFFERED_BYTES = Setting.byteSizeSetting(
        "search.arrow.coordinator.max_buffered_bytes",
        new ByteSizeValue(512, ByteSizeUnit.MB),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(Long.MAX_VALUE),
        Property.NodeScope,
        Property.Dynamic
    );

    /**
     * How long a client may stop reading before its search fails, releasing everything the search holds, and, once the
     * search ended, before its call is reset, releasing the batches the client has not read.
     */
    public static final Setting<TimeValue> CLIENT_IDLE_TIMEOUT = Setting.positiveTimeSetting(
        "search.arrow.client_idle_timeout",
        TimeValue.timeValueSeconds(60),
        Property.NodeScope,
        Property.Dynamic
    );

    /**
     * Cancels Arrow searches that run longer than this, unless the request sets its own interval. Unlike
     * {@code search.cancel_after_time_interval}, which targets interactive searches, it is off by default, because an export
     * runs as long as its client reads.
     */
    public static final Setting<TimeValue> CANCEL_AFTER_TIME_INTERVAL = Setting.timeSetting(
        "search.arrow.cancel_after_time_interval",
        TimeValue.MINUS_ONE,
        TimeValue.MINUS_ONE,
        Property.NodeScope,
        Property.Dynamic
    );

    /** How many shard scans may be open on a data node at once. */
    public static final Setting<Integer> SHARD_MAX_OPEN_SCANS = new Setting<>(
        "search.arrow.shard.max_open_scans",
        settings -> Integer.toString(4 * OpenSearchExecutors.allocatedProcessors(settings)),
        value -> Setting.parseInt(value, 1, "search.arrow.shard.max_open_scans"),
        Property.NodeScope,
        Property.Dynamic
    );

    /**
     * How many shard scans may compute on a data node at once. Scans compute on virtual threads, and hold no slot while they
     * wait for their consumer.
     */
    public static final Setting<Integer> SHARD_MAX_CONCURRENT_BATCHES = new Setting<>(
        "search.arrow.shard.max_concurrent_batches",
        settings -> Integer.toString(Math.max(1, ThreadPool.searchThreadPoolSize(OpenSearchExecutors.allocatedProcessors(settings)) / 2)),
        value -> Setting.parseInt(value, 1, "search.arrow.shard.max_concurrent_batches"),
        Property.NodeScope,
        Property.Dynamic
    );

    /** How long a new shard scan waits for an open slot before it is rejected, and retried on another copy. */
    public static final Setting<TimeValue> SHARD_ADMISSION_WAIT = Setting.timeSetting(
        "search.arrow.shard.admission_wait",
        TimeValue.timeValueSeconds(5),
        TimeValue.ZERO,
        Property.NodeScope,
        Property.Dynamic
    );

    /** The batch memory all shard scans may hold on a data node. */
    public static final Setting<ByteSizeValue> SHARD_MAX_BUFFERED_BYTES = Setting.byteSizeSetting(
        "search.arrow.shard.max_buffered_bytes",
        new ByteSizeValue(512, ByteSizeUnit.MB),
        new ByteSizeValue(1, ByteSizeUnit.MB),
        new ByteSizeValue(Long.MAX_VALUE),
        Property.NodeScope
    );

    /**
     * @return every setting of the plugin
     */
    public static List<Setting<?>> all() {
        return List.of(
            BATCH_SIZE,
            FIRST_BATCH_SIZE,
            BATCH_ROWS,
            MAX_ROW_SIZE,
            MAX_BUFFERED_BYTES_PER_REQUEST,
            COORDINATOR_MAX_BUFFERED_BYTES,
            CLIENT_IDLE_TIMEOUT,
            CANCEL_AFTER_TIME_INTERVAL,
            SHARD_MAX_OPEN_SCANS,
            SHARD_MAX_CONCURRENT_BATCHES,
            SHARD_ADMISSION_WAIT,
            SHARD_MAX_BUFFERED_BYTES
        );
    }

    private volatile long batchBytes;
    private volatile long firstBatchBytes;
    private volatile int batchRows;
    private volatile long maxRowBytes;
    private volatile long maxBufferedBytesPerRequest;
    private volatile long coordinatorMaxBufferedBytes;
    private volatile TimeValue clientIdleTimeout;
    private volatile TimeValue cancelAfterTimeInterval;
    private volatile TimeValue admissionWait;

    /**
     * @param settings        the node settings
     * @param clusterSettings the cluster settings, which update the dynamic values
     */
    public ArrowSearchSettings(Settings settings, ClusterSettings clusterSettings) {
        batchBytes = BATCH_SIZE.get(settings).getBytes();
        firstBatchBytes = FIRST_BATCH_SIZE.get(settings).getBytes();
        batchRows = BATCH_ROWS.get(settings);
        maxRowBytes = MAX_ROW_SIZE.get(settings).getBytes();
        maxBufferedBytesPerRequest = MAX_BUFFERED_BYTES_PER_REQUEST.get(settings).getBytes();
        coordinatorMaxBufferedBytes = COORDINATOR_MAX_BUFFERED_BYTES.get(settings).getBytes();
        clientIdleTimeout = CLIENT_IDLE_TIMEOUT.get(settings);
        cancelAfterTimeInterval = CANCEL_AFTER_TIME_INTERVAL.get(settings);
        admissionWait = SHARD_ADMISSION_WAIT.get(settings);
        clusterSettings.addSettingsUpdateConsumer(BATCH_SIZE, value -> batchBytes = value.getBytes());
        clusterSettings.addSettingsUpdateConsumer(FIRST_BATCH_SIZE, value -> firstBatchBytes = value.getBytes());
        clusterSettings.addSettingsUpdateConsumer(BATCH_ROWS, value -> batchRows = value);
        clusterSettings.addSettingsUpdateConsumer(MAX_ROW_SIZE, value -> maxRowBytes = value.getBytes());
        clusterSettings.addSettingsUpdateConsumer(MAX_BUFFERED_BYTES_PER_REQUEST, value -> maxBufferedBytesPerRequest = value.getBytes());
        clusterSettings.addSettingsUpdateConsumer(COORDINATOR_MAX_BUFFERED_BYTES, value -> coordinatorMaxBufferedBytes = value.getBytes());
        clusterSettings.addSettingsUpdateConsumer(CLIENT_IDLE_TIMEOUT, value -> clientIdleTimeout = value);
        clusterSettings.addSettingsUpdateConsumer(CANCEL_AFTER_TIME_INTERVAL, value -> cancelAfterTimeInterval = value);
        clusterSettings.addSettingsUpdateConsumer(SHARD_ADMISSION_WAIT, value -> admissionWait = value);
    }

    public long batchBytes() {
        return batchBytes;
    }

    public long firstBatchBytes() {
        return Math.min(firstBatchBytes, batchBytes);
    }

    public int batchRows() {
        return batchRows;
    }

    public long maxRowBytes() {
        return maxRowBytes;
    }

    public long maxBufferedBytesPerRequest() {
        return maxBufferedBytesPerRequest;
    }

    public long coordinatorMaxBufferedBytes() {
        return coordinatorMaxBufferedBytes;
    }

    public TimeValue clientIdleTimeout() {
        return clientIdleTimeout;
    }

    public TimeValue cancelAfterTimeInterval() {
        return cancelAfterTimeInterval;
    }

    public TimeValue admissionWait() {
        return admissionWait;
    }

}
