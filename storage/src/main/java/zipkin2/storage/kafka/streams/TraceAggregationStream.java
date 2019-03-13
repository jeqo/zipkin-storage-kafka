/*
 * Copyright 2019 jeqo
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.storage.kafka.streams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;
import zipkin2.Span;
import zipkin2.storage.kafka.streams.serdes.SpanSerde;
import zipkin2.storage.kafka.streams.serdes.SpansSerde;

/**
 * Aggregation of Spans partitioned by TraceId into a Trace ChangeLog
 */
public class TraceAggregationStream implements Supplier<Topology> {
  // Kafka topics
  final String traceSpansTopic;
  final String tracesTopic;

  // Store names
  final String tracesStoreName;

  // SerDes
  final SpanSerde spanSerde;
  final SpansSerde spansSerde;

  public TraceAggregationStream(
      String traceSpansTopic,
      String tracesStoreName,
      String tracesTopic) {
    this.traceSpansTopic = traceSpansTopic;
    this.tracesStoreName = tracesStoreName;
    this.tracesTopic = tracesTopic;

    spanSerde = new SpanSerde();
    spansSerde = new SpansSerde();
  }

  @Override public Topology get() {
    StreamsBuilder builder = new StreamsBuilder();

    // Aggregate Spans to Traces
    builder.stream(traceSpansTopic, Consumed.with(Serdes.String(), spanSerde))
        .groupByKey()
        .aggregate(ArrayList::new, (traceId, span, spans) -> {
              if (span == null) { // Cleaning state
                return null;
              } else {
                if (spans == null) {
                  return Collections.singletonList(span);
                } else {
                  spans.add(span);
                  return spans;
                }
              }
            },
            Materialized.<String, List<Span>, KeyValueStore<Bytes, byte[]>>as(tracesStoreName)
                .withKeySerde(Serdes.String())
                .withValueSerde(spansSerde)
                .withCachingEnabled()
                .withLoggingDisabled())
        .filter((key, value) -> Objects.nonNull(value))
        .toStream()
        .to(tracesTopic, Produced.with(Serdes.String(), spansSerde));
    return builder.build();
  }
}