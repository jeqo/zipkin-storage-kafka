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
package zipkin2.storage.kafka;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Call;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanStore;
import zipkin2.storage.kafka.internal.KafkaStreamsStoreCall;
import zipkin2.storage.kafka.streams.DependencyStoreTopologySupplier;
import zipkin2.storage.kafka.streams.TraceStoreTopologySupplier;

import static zipkin2.storage.kafka.streams.DependencyStoreTopologySupplier.DEPENDENCIES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.REMOTE_SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.SPAN_IDS_BY_TS_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.SPAN_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.TRACES_STORE_NAME;

/**
 * Span store backed by Kafka Stream local stores built by {@link TraceStoreTopologySupplier} and
 * {@link DependencyStoreTopologySupplier}.
 * <p>
 * These stores are currently supporting only single instance as there is not mechanism implemented
 * for scatter gather data from different instances.
 */
public class KafkaSpanStore implements SpanStore, ServiceAndSpanNames {
  static final Logger LOG = LoggerFactory.getLogger(KafkaSpanStore.class);
  // Kafka Streams Store provider
  final KafkaStreams traceStoreStream;
  final KafkaStreams dependencyStoreStream;

  KafkaSpanStore(KafkaStorage storage) {
    traceStoreStream = storage.getTraceStoreStream();
    dependencyStoreStream = storage.getDependencyStoreStream();
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    ReadOnlyKeyValueStore<String, List<Span>> tracesStore =
        traceStoreStream.store(TRACES_STORE_NAME, QueryableStoreTypes.keyValueStore());
    ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore =
        traceStoreStream.store(SPAN_IDS_BY_TS_STORE_NAME, QueryableStoreTypes.keyValueStore());
    return new GetTracesCall(tracesStore, traceIdsByTsStore, request);
  }

  @Override
  public Call<List<Span>> getTrace(String traceId) {
    ReadOnlyKeyValueStore<String, List<Span>> traceStore =
        traceStoreStream.store(TRACES_STORE_NAME, QueryableStoreTypes.keyValueStore());
    return new GetTraceCall(traceStore, traceId);
  }

  @Deprecated @Override public Call<List<String>> getServiceNames() {
    ReadOnlyKeyValueStore<String, String> serviceStore =
        traceStoreStream.store(SERVICE_NAMES_STORE_NAME, QueryableStoreTypes.keyValueStore());
    return new GetServiceNamesCall(serviceStore);
  }

  @Deprecated @Override public Call<List<String>> getSpanNames(String serviceName) {
    ReadOnlyKeyValueStore<String, Set<String>> spanNamesStore =
        traceStoreStream.store(SPAN_NAMES_STORE_NAME, QueryableStoreTypes.keyValueStore());
    return new GetSpanNamesCall(spanNamesStore, serviceName);
  }

  @Override public Call<List<String>> getRemoteServiceNames(String serviceName) {
    ReadOnlyKeyValueStore<String, Set<String>> remoteServiceNamesStore =
        traceStoreStream.store(REMOTE_SERVICE_NAMES_STORE_NAME,
            QueryableStoreTypes.keyValueStore());
    return new GetRemoteServiceNamesCall(remoteServiceNamesStore, serviceName);
  }

  @Override public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    ReadOnlyWindowStore<Long, DependencyLink> dependenciesStore =
        dependencyStoreStream.store(DEPENDENCIES_STORE_NAME,
            QueryableStoreTypes.windowStore());
    return new GetDependenciesCall(endTs, lookback, dependenciesStore);
  }

  static class GetServiceNamesCall extends KafkaStreamsStoreCall<List<String>> {
    ReadOnlyKeyValueStore<String, String> serviceStore;

    GetServiceNamesCall(ReadOnlyKeyValueStore<String, String> serviceStore) {
      this.serviceStore = serviceStore;
    }

    @Override public List<String> query() {
      List<String> serviceNames = new ArrayList<>();
      try (KeyValueIterator<String, String> all = serviceStore.all()) {
        all.forEachRemaining(keyValue -> {
          // double check service names are unique
          if (!serviceNames.contains(keyValue.value)) serviceNames.add(keyValue.value);
        });
      }
      // comply with Zipkin API as service names are required to be ordered lexicographically
      Collections.sort(serviceNames);
      return serviceNames;
    }

    @Override public Call<List<String>> clone() {
      return new GetServiceNamesCall(serviceStore);
    }
  }

  static class GetSpanNamesCall extends KafkaStreamsStoreCall<List<String>> {
    final ReadOnlyKeyValueStore<String, Set<String>> spanNamesStore;
    final String serviceName;

    GetSpanNamesCall(ReadOnlyKeyValueStore<String, Set<String>> spanNamesStore,
        String serviceName) {
      this.spanNamesStore = spanNamesStore;
      this.serviceName = serviceName;
    }

    @Override public List<String> query() {
      if (serviceName == null) return new ArrayList<>();
      Set<String> spanNamesSet = spanNamesStore.get(serviceName);
      if (spanNamesSet == null) return new ArrayList<>();
      List<String> spanNames = new ArrayList<>(spanNamesSet);
      // comply with Zipkin API as service names are required to be ordered lexicographically and store returns unordered values
      Collections.sort(spanNames);
      return spanNames;
    }

    @Override public Call<List<String>> clone() {
      return new GetSpanNamesCall(spanNamesStore, serviceName);
    }
  }

  static class GetRemoteServiceNamesCall extends KafkaStreamsStoreCall<List<String>> {
    final ReadOnlyKeyValueStore<String, Set<String>> remoteServiceNamesStore;
    final String serviceName;

    GetRemoteServiceNamesCall(ReadOnlyKeyValueStore<String, Set<String>> remoteServiceNamesStore,
        String serviceName) {
      this.remoteServiceNamesStore = remoteServiceNamesStore;
      this.serviceName = serviceName;
    }

    @Override public List<String> query() {
      if (serviceName == null) return new ArrayList<>();
      Set<String> remoteServiceNamesSet = remoteServiceNamesStore.get(serviceName);
      if (remoteServiceNamesSet == null) return new ArrayList<>();
      List<String> remoteServiceNames = new ArrayList<>(remoteServiceNamesSet);
      // comply with Zipkin API as service names are required to be ordered lexicographically
      Collections.sort(remoteServiceNames);
      return remoteServiceNames;
    }

    @Override public Call<List<String>> clone() {
      return new GetRemoteServiceNamesCall(remoteServiceNamesStore, serviceName);
    }
  }

  static class GetTracesCall extends KafkaStreamsStoreCall<List<List<Span>>> {
    final ReadOnlyKeyValueStore<String, List<Span>> tracesStore;
    final ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore;
    final QueryRequest request;

    GetTracesCall(
        ReadOnlyKeyValueStore<String, List<Span>> tracesStore,
        ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore,
        QueryRequest request) {
      this.tracesStore = tracesStore;
      this.traceIdsByTsStore = traceIdsByTsStore;
      this.request = request;
    }

    @Override public List<List<Span>> query() {
      List<List<Span>> traces = new ArrayList<>();
      List<String> traceIds = new ArrayList<>();
      // milliseconds to microseconds
      long from = (request.endTs() - request.lookback()) * 1000;
      long to = request.endTs() * 1000;
      // first index
      try (KeyValueIterator<Long, Set<String>> spanIds = traceIdsByTsStore.range(from, to)) {
        spanIds.forEachRemaining(keyValue -> {
          for (String traceId : keyValue.value) {
            if (!traceIds.contains(traceId)) {
              List<Span> spans = tracesStore.get(traceId);
              if (spans != null && request.test(spans)) { // apply filters
                traceIds.add(traceId); // adding to check if we have already add it later
                traces.add(spans);
              }
            }
          }
        });
      }
      traces.sort(Comparator.<List<Span>>comparingLong(o -> o.get(0).timestampAsLong()).reversed());
      LOG.debug("Traces found from query {}: {}", request, traces.size());
      return traces.subList(0,
          request.limit() >= traces.size() ? traceIds.size() : request.limit());
    }

    @Override
    public Call<List<List<Span>>> clone() {
      return new GetTracesCall(tracesStore, traceIdsByTsStore, request);
    }
  }

  static class GetTraceCall extends KafkaStreamsStoreCall<List<Span>> {
    final ReadOnlyKeyValueStore<String, List<Span>> traceStore;
    final String traceId;

    GetTraceCall(ReadOnlyKeyValueStore<String, List<Span>> traceStore, String traceId) {
      this.traceStore = traceStore;
      this.traceId = traceId;
    }

    @Override public List<Span> query() {
      final List<Span> spans = traceStore.get(traceId);
      if (spans == null) return new ArrayList<>();
      return spans;
    }

    @Override public Call<List<Span>> clone() {
      return new GetTraceCall(traceStore, traceId);
    }
  }

  static class GetDependenciesCall extends KafkaStreamsStoreCall<List<DependencyLink>> {
    final long endTs, loopback;
    final ReadOnlyWindowStore<Long, DependencyLink> dependenciesStore;

    GetDependenciesCall(long endTs, long loopback,
        ReadOnlyWindowStore<Long, DependencyLink> dependenciesStore) {
      this.endTs = endTs;
      this.loopback = loopback;
      this.dependenciesStore = dependenciesStore;
    }

    @Override public List<DependencyLink> query() {
      List<DependencyLink> links = new ArrayList<>();
      Instant from = Instant.ofEpochMilli(endTs - loopback);
      Instant to = Instant.ofEpochMilli(endTs);
      dependenciesStore.fetchAll(from, to)
          .forEachRemaining(keyValue -> links.add(keyValue.value));
      List<DependencyLink> mergedLinks = DependencyLinker.merge(links);
      LOG.debug("Dependencies found from={}-to={}: {}", from, to, mergedLinks.size());
      return mergedLinks;
    }

    @Override public Call<List<DependencyLink>> clone() {
      return new GetDependenciesCall(endTs, loopback, dependenciesStore);
    }
  }
}
