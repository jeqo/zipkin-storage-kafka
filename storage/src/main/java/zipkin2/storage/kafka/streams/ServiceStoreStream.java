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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import zipkin2.storage.kafka.streams.serdes.SpanNamesSerde;

public class ServiceStoreStream implements Supplier<Topology> {

  // Topic names
  final String serviceSpanNamesTopic;

  // Store names
  final String globalServicesStoreName;

  // SerDes
  final SpanNamesSerde spanNamesSerde;

  public ServiceStoreStream(
      String serviceSpanNamesTopic,
      String globalServicesStoreName) {
    this.serviceSpanNamesTopic = serviceSpanNamesTopic;
    this.globalServicesStoreName = globalServicesStoreName;

    spanNamesSerde = new SpanNamesSerde();
  }

  @Override public Topology get() {
    // Preparing state stores
    StoreBuilder<KeyValueStore<String, Set<String>>> globalServiceStoreBuilder =
        Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(globalServicesStoreName),
            Serdes.String(),
            spanNamesSerde)
            .withCachingEnabled()
            .withLoggingDisabled();

    StreamsBuilder builder = new StreamsBuilder();

    // Aggregate Service:SpanNames
    builder
        .addGlobalStore(
            globalServiceStoreBuilder,
            serviceSpanNamesTopic,
            Consumed.with(Serdes.String(), Serdes.String()),
            () -> new Processor<String, String>() {
              KeyValueStore<String, Set<String>> servicesStore;

              @Override public void init(ProcessorContext context) {
                servicesStore =
                    (KeyValueStore<String, Set<String>>) context.getStateStore(
                        globalServicesStoreName);
              }

              @Override public void process(String serviceName, String spanName) {
                Set<String> currentSpanNames = servicesStore.get(serviceName);
                if (currentSpanNames == null) {
                  final HashSet<String> spanNames = new HashSet<>();
                  spanNames.add(spanName);
                  servicesStore.put(serviceName, spanNames);
                } else {
                  currentSpanNames.add(spanName);
                  servicesStore.put(serviceName, currentSpanNames);
                }
              }

              @Override public void close() { // Nothing to close
              }
            });

    return builder.build();
  }
}