/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

/**
 * Observer the coordinator-reduce backend invokes around each reduced-output batch as it drains the
 * native FINAL-aggregation stream downstream. Lets the engine layer time the reduce execution
 * (produce vs send, mirroring the data-node per-batch split) <em>without</em> the framework SPI or
 * any backend depending on the engine's tracing types.
 *
 * <p>Decoupling contract (mirrors {@link ExchangeSinkContext#nativePeakRecorder}): every method
 * argument is a primitive or an opaque {@code Object} token — there is no
 * {@code org.opensearch.telemetry.tracing} type here. The engine implementation returns a token
 * from {@code onProduceStart}/{@code onSendStart} (the underlying span, or {@code null} when tracing
 * is off or the per-stage span cap is reached) and consumes it in the matching {@code *End} call.
 * The backend treats the token as opaque and never inspects it.
 *
 * <p>All methods must be cheap and must not throw — the reduce drain runs on the REDUCE pool and a
 * thrown observer must never corrupt the drain's batch-ownership/teardown path.
 *
 * @opensearch.internal
 */
public interface ReduceDrainObserver {

    /** No-op observer used by back-compat constructors and non-instrumented paths. */
    ReduceDrainObserver NOOP = new ReduceDrainObserver() {
    };

    /** Called before the native pull of reduced-output batch {@code ordinal}. Returns an opaque token. */
    default Object onProduceStart(long ordinal) {
        return null;
    }

    /** Called after the native pull completes (or fails). {@code token} is from {@link #onProduceStart}. */
    default void onProduceEnd(Object token, long rows, long arrowBytes, long produceNanos, Throwable error) {
    }

    /** Called before pushing the reduced batch downstream. Returns an opaque token. */
    default Object onSendStart(long ordinal) {
        return null;
    }

    /** Called after the downstream push completes (or fails). {@code token} is from {@link #onSendStart}. */
    default void onSendEnd(Object token, long rows, long sendNanos, Throwable error) {
    }

    /** Called once when the drain finishes, with the total reduced-output batch count (for cap-elision visibility). */
    default void onDrainComplete(long totalBatches) {
    }
}
