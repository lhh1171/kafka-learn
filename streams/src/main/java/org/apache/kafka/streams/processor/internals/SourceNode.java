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
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.apache.kafka.streams.processor.internals.metrics.ProcessorNodeMetrics;

import static org.apache.kafka.streams.kstream.internals.WrappingNullableUtils.prepareKeyDeserializer;
import static org.apache.kafka.streams.kstream.internals.WrappingNullableUtils.prepareValueDeserializer;

public class SourceNode<K, V> extends ProcessorNode<K, V> {

    private InternalProcessorContext context;
    private Deserializer<K> keyDeserializer;
    private Deserializer<V> valDeserializer;
    private final TimestampExtractor timestampExtractor;
    private Sensor processAtSourceSensor;

    public SourceNode(final String name,
                      final TimestampExtractor timestampExtractor,
                      final Deserializer<K> keyDeserializer,
                      final Deserializer<V> valDeserializer) {
        super(name);
        this.timestampExtractor = timestampExtractor;
        this.keyDeserializer = keyDeserializer;
        this.valDeserializer = valDeserializer;
    }

    public SourceNode(final String name,
                      final Deserializer<K> keyDeserializer,
                      final Deserializer<V> valDeserializer) {
        this(name, null, keyDeserializer, valDeserializer);
    }

    K deserializeKey(final String topic, final Headers headers, final byte[] data) {
        return keyDeserializer.deserialize(topic, headers, data);
    }

    V deserializeValue(final String topic, final Headers headers, final byte[] data) {
        return valDeserializer.deserialize(topic, headers, data);
    }

    @Override
    public void init(final InternalProcessorContext context) {
        // It is important to first create the sensor before calling init on the
        // parent object. Otherwise due to backwards compatibility an empty sensor
        // without parent is created with the same name.
        // Once the backwards compatibility is not needed anymore it might be possible to
        // change this.
        processAtSourceSensor = ProcessorNodeMetrics.processorAtSourceSensorOrForwardSensor(
            Thread.currentThread().getName(),
            context.taskId().toString(),
            context.currentNode().name(),
            context.metrics()
        );
        super.init(context);
        this.context = context;

        final Deserializer<?> contextKeyDeserializer = ProcessorContextUtils.getKeyDeserializer(context);
        final Deserializer<?> contextValueDeserializer = ProcessorContextUtils.getValueDeserializer(context);
        keyDeserializer = prepareKeyDeserializer(keyDeserializer, contextKeyDeserializer, contextValueDeserializer);
        valDeserializer = prepareValueDeserializer(valDeserializer, contextKeyDeserializer, contextValueDeserializer);
    }


    @Override
    public void process(final K key, final V value) {
        context.forward(key, value);
        processAtSourceSensor.record(1.0d, context.currentSystemTimeMs());
    }

    /**
     * @return a string representation of this node, useful for debugging.
     */
    @Override
    public String toString() {
        return toString("");
    }

    public TimestampExtractor getTimestampExtractor() {
        return timestampExtractor;
    }
}