/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.WrappingNullableUtils;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.StateSerdes;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.state.internals.metrics.StateStoreMetrics;

import static org.apache.kafka.streams.kstream.internals.WrappingNullableUtils.prepareKeySerde;
import static org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl.maybeMeasureLatency;

public class MeteredWindowStore<K, V>
    extends WrappedStateStore<WindowStore<Bytes, byte[]>, Windowed<K>, V>
    implements WindowStore<K, V> {

    private final long windowSizeMs;
    private final String metricsScope;
    private final Time time;
    final Serde<K> keySerde;
    final Serde<V> valueSerde;
    StateSerdes<K, V> serdes;
    private StreamsMetricsImpl streamsMetrics;
    private Sensor putSensor;
    private Sensor fetchSensor;
    private Sensor flushSensor;
    private ProcessorContext context;
    private final String threadId;
    private String taskId;

    MeteredWindowStore(final WindowStore<Bytes, byte[]> inner,
                       final long windowSizeMs,
                       final String metricsScope,
                       final Time time,
                       final Serde<K> keySerde,
                       final Serde<V> valueSerde) {
        super(inner);
        this.windowSizeMs = windowSizeMs;
        threadId = Thread.currentThread().getName();
        this.metricsScope = metricsScope;
        this.time = time;
        this.keySerde = keySerde;
        this.valueSerde = valueSerde;
    }

    @Override
    public void init(final ProcessorContext context,
                     final StateStore root) {
        this.context = context;
        initStoreSerde(context);
        streamsMetrics = (StreamsMetricsImpl) context.metrics();
        taskId = context.taskId().toString();

        putSensor = StateStoreMetrics.putSensor(threadId, taskId, metricsScope, name(), streamsMetrics);
        fetchSensor = StateStoreMetrics.fetchSensor(threadId, taskId, metricsScope, name(), streamsMetrics);
        flushSensor = StateStoreMetrics.flushSensor(threadId, taskId, metricsScope, name(), streamsMetrics);
        final Sensor restoreSensor =
            StateStoreMetrics.restoreSensor(threadId, taskId, metricsScope, name(), streamsMetrics);

        // register and possibly restore the state from the logs
        maybeMeasureLatency(() -> super.init(context, root), time, restoreSensor);
    }
    protected Serde<V> prepareValueSerde(final Serde<V> valueSerde, final Serde<?> contextKeySerde, final Serde<?> contextValueSerde) {
        return WrappingNullableUtils.prepareValueSerde(valueSerde, contextKeySerde, contextValueSerde);
    }

    void initStoreSerde(final ProcessorContext context) {
        serdes = new StateSerdes<>(
            ProcessorStateManager.storeChangelogTopic(context.applicationId(), name()),
            prepareKeySerde(keySerde, context.keySerde(), context.valueSerde()),
            prepareValueSerde(valueSerde, context.keySerde(), context.valueSerde()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean setFlushListener(final CacheFlushListener<Windowed<K>, V> listener,
                                    final boolean sendOldValues) {
        final WindowStore<Bytes, byte[]> wrapped = wrapped();
        if (wrapped instanceof CachedStateStore) {
            return ((CachedStateStore<byte[], byte[]>) wrapped).setFlushListener(
                (key, newValue, oldValue, timestamp) -> listener.apply(
                    WindowKeySchema.fromStoreKey(key, windowSizeMs, serdes.keyDeserializer(), serdes.topic()),
                    newValue != null ? serdes.valueFrom(newValue) : null,
                    oldValue != null ? serdes.valueFrom(oldValue) : null,
                    timestamp
                ),
                sendOldValues);
        }
        return false;
    }

    @Deprecated
    @Override
    public void put(final K key,
                    final V value) {
        put(key, value, context.timestamp());
    }

    @Override
    public void put(final K key,
                    final V value,
                    final long windowStartTimestamp) {
        try {
            maybeMeasureLatency(
                () -> wrapped().put(keyBytes(key), serdes.rawValue(value), windowStartTimestamp),
                time,
                putSensor
            );
        } catch (final ProcessorStateException e) {
            final String message = String.format(e.getMessage(), key, value);
            throw new ProcessorStateException(message, e);
        }
    }

    @Override
    public V fetch(final K key,
                   final long timestamp) {
        return maybeMeasureLatency(
            () -> {
                final byte[] result = wrapped().fetch(keyBytes(key), timestamp);
                if (result == null) {
                    return null;
                }
                return serdes.valueFrom(result);
            },
            time,
            fetchSensor
        );
    }

    @SuppressWarnings("deprecation") // note, this method must be kept if super#fetch(...) is removed
    @Override
    public WindowStoreIterator<V> fetch(final K key,
                                        final long timeFrom,
                                        final long timeTo) {
        return new MeteredWindowStoreIterator<>(
            wrapped().fetch(keyBytes(key), timeFrom, timeTo),
            fetchSensor,
            streamsMetrics,
            serdes,
            time
        );
    }

    @SuppressWarnings("deprecation") // note, this method must be kept if super#fetchAll(...) is removed
    @Override
    public KeyValueIterator<Windowed<K>, V> fetch(final K from,
                                                  final K to,
                                                  final long timeFrom,
                                                  final long timeTo) {
        return new MeteredWindowedKeyValueIterator<>(
            wrapped().fetch(keyBytes(from), keyBytes(to), timeFrom, timeTo),
            fetchSensor,
            streamsMetrics,
            serdes,
            time);
    }

    @SuppressWarnings("deprecation") // note, this method must be kept if super#fetch(...) is removed
    @Override
    public KeyValueIterator<Windowed<K>, V> fetchAll(final long timeFrom,
                                                     final long timeTo) {
        return new MeteredWindowedKeyValueIterator<>(
            wrapped().fetchAll(timeFrom, timeTo),
            fetchSensor,
            streamsMetrics,
            serdes,
            time);
    }

    @Override
    public KeyValueIterator<Windowed<K>, V> all() {
        return new MeteredWindowedKeyValueIterator<>(wrapped().all(), fetchSensor, streamsMetrics, serdes, time);
    }

    @Override
    public void flush() {
        maybeMeasureLatency(super::flush, time, flushSensor);
    }

    @Override
    public void close() {
        try {
            wrapped().close();
        } finally {
            streamsMetrics.removeAllStoreLevelSensors(threadId, taskId, name());
        }
    }

    private Bytes keyBytes(final K key) {
        return Bytes.wrap(serdes.rawKey(key));
    }
}
