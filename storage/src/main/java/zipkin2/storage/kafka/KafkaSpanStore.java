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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
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
import zipkin2.Callback;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanStore;

import static zipkin2.storage.kafka.streams.TraceStoreSupplier.DEPENDENCY_LINKS_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreSupplier.REMOTE_SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreSupplier.SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreSupplier.SPAN_IDS_BY_TS_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreSupplier.SPAN_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreSupplier.TRACES_STORE_NAME;

/**
 * Span Store based on Kafka Streams.
 *
 * This store supports all searches (e.g. findTraces, getTrace, getServiceNames, getSpanNames, and
 * getDependencies).
 *
 * NOTE: Currently State Stores are based on global state stores (i.e., all data is replicated on
 * every Zipkin instance with spanStoreEnabled=true).
 */
public class KafkaSpanStore implements SpanStore, ServiceAndSpanNames {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaSpanStore.class);
  // Kafka Streams
  final KafkaStreams traceStoreStream;

  KafkaSpanStore(KafkaStorage storage) {
    traceStoreStream = storage.getTraceStoreStream();
  }

  @Override
  public Call<List<List<Span>>> getTraces(QueryRequest request) {
    try {
      ReadOnlyKeyValueStore<String, List<Span>> tracesStore =
          traceStoreStream.store(TRACES_STORE_NAME, QueryableStoreTypes.keyValueStore());
      ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore =
          traceStoreStream.store(SPAN_IDS_BY_TS_STORE_NAME, QueryableStoreTypes.keyValueStore());
      return new GetTracesCall(tracesStore, traceIdsByTsStore, request);
    } catch (Exception e) {
      LOG.error("Error getting traces. Request: {}", request, e);
      return Call.emptyList();
    }
  }

  @Override
  public Call<List<Span>> getTrace(String traceId) {
    try {
      ReadOnlyKeyValueStore<String, List<Span>> traceStore =
          traceStoreStream.store(TRACES_STORE_NAME, QueryableStoreTypes.keyValueStore());
      return new GetTraceCall(traceStore, traceId);
    } catch (Exception e) {
      LOG.error("Error getting trace {}", traceId, e);
      return Call.emptyList();
    }
  }

  @Override
  public Call<List<String>> getServiceNames() {
    try {
      ReadOnlyKeyValueStore<String, String> serviceStore =
          traceStoreStream.store(SERVICE_NAMES_STORE_NAME, QueryableStoreTypes.keyValueStore());
      return new GetServiceNamesCall(serviceStore);
    } catch (Exception e) {
      LOG.error("Error getting service names", e);
      return Call.emptyList();
    }
  }

  @Override
  public Call<List<String>> getRemoteServiceNames(String serviceName) {
    try {
      ReadOnlyKeyValueStore<String, Set<String>> remoteServiceNamesStore =
          traceStoreStream.store(REMOTE_SERVICE_NAMES_STORE_NAME, QueryableStoreTypes.keyValueStore());
      return new GetRemoteServiceNamesCall(remoteServiceNamesStore, serviceName);
    } catch (Exception e) {
      LOG.error("Error getting remote service names from service {}", serviceName, e);
      return Call.emptyList();
    }
  }

  @Override
  public Call<List<String>> getSpanNames(String serviceName) {
    try {
      ReadOnlyKeyValueStore<String, Set<String>> spanNamesStore =
          traceStoreStream.store(SPAN_NAMES_STORE_NAME, QueryableStoreTypes.keyValueStore());
      return new GetSpanNamesCall(spanNamesStore, serviceName);
    } catch (Exception e) {
      LOG.error("Error getting span names from service {}", serviceName, e);
      return Call.emptyList();
    }
  }

  @Override
  public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    try {
      ReadOnlyWindowStore<Long, DependencyLink> dependenciesStore =
          traceStoreStream.store(DEPENDENCY_LINKS_STORE_NAME,
              QueryableStoreTypes.windowStore());
      return new GetDependenciesCall(endTs, lookback, dependenciesStore);
    } catch (Exception e) {
      LOG.error("Error getting dependencies", e);
      return Call.emptyList();
    }
  }

  static class GetServiceNamesCall extends KafkaStreamsStoreCall<List<String>> {
    ReadOnlyKeyValueStore<String, String> serviceStore;

    GetServiceNamesCall(ReadOnlyKeyValueStore<String, String> serviceStore) {
      this.serviceStore = serviceStore;
    }

    @Override
    List<String> query() {
      try {
        List<String> serviceNames = new ArrayList<>();
        serviceStore.all().forEachRemaining(keyValue -> serviceNames.add(keyValue.value));
        Collections.sort(serviceNames);
        return serviceNames;
      } catch (Exception e) {
        LOG.error("Error looking up services", e);
        return new ArrayList<>();
      }
    }

    @Override
    public Call<List<String>> clone() {
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

    @Override
    List<String> query() {
      try {
        if (serviceName == null || serviceName.equals("all")) return new ArrayList<>();
        Set<String> spanNamesSet = spanNamesStore.get(serviceName);
        if (spanNamesSet == null) return new ArrayList<>();
        List<String> spanNames = new ArrayList<>(spanNamesSet);
        Collections.sort(spanNames);
        return spanNames;
      } catch (Exception e) {
        LOG.error("Error looking up for span names for service {}", serviceName, e);
        return new ArrayList<>();
      }
    }

    @Override
    public Call<List<String>> clone() {
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

    @Override
    List<String> query() {
      try {
        if (serviceName == null || serviceName.equals("all")) return new ArrayList<>();
        Set<String> remoteServiceNamesSet = remoteServiceNamesStore.get(serviceName);
        if (remoteServiceNamesSet == null) return new ArrayList<>();
        List<String> remoteServiceNames = new ArrayList<>(remoteServiceNamesSet);
        Collections.sort(remoteServiceNames);
        return remoteServiceNames;
      } catch (Exception e) {
        LOG.error("Error looking up for remote service names for service {}", serviceName, e);
        return new ArrayList<>();
      }
    }

    @Override
    public Call<List<String>> clone() {
      return new GetRemoteServiceNamesCall(remoteServiceNamesStore, serviceName);
    }
  }

  static class GetTracesCall extends KafkaStreamsStoreCall<List<List<Span>>> {
    final ReadOnlyKeyValueStore<String, List<Span>> tracesStore;
    final ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore;
    final QueryRequest queryRequest;

    GetTracesCall(
        ReadOnlyKeyValueStore<String, List<Span>> tracesStore,
        ReadOnlyKeyValueStore<Long, Set<String>> traceIdsByTsStore,
        QueryRequest queryRequest) {
      this.tracesStore = tracesStore;
      this.traceIdsByTsStore = traceIdsByTsStore;
      this.queryRequest = queryRequest;
    }

    @Override
    List<List<Span>> query() {
      List<List<Span>> result = new ArrayList<>();
      List<String> traceIds = new ArrayList<>();
      traceIdsByTsStore.all().forEachRemaining(System.out::println);
      // milliseconds to microseconds
      long from = (queryRequest.endTs() - queryRequest.lookback()) * 1000;
      long to = queryRequest.endTs() * 1000;
      KeyValueIterator<Long, Set<String>> spanIds = traceIdsByTsStore.range(from, to);
      spanIds.forEachRemaining(keyValue -> {
        for (String traceId : keyValue.value) {
          if (!traceIds.contains(traceId) && result.size() <= queryRequest.limit()) {
            List<Span> spans = tracesStore.get(traceId);
            if (queryRequest.test(spans)) {
              traceIds.add(traceId);
              result.add(spans);
            }
          }
        }
      });

      LOG.info("Total results of query {}: {}", queryRequest, result.size());

      return result;
    }

    @Override
    public Call<List<List<Span>>> clone() {
      return new GetTracesCall(tracesStore, traceIdsByTsStore, queryRequest);
    }
  }

  static class GetTraceCall extends KafkaStreamsStoreCall<List<Span>> {
    final ReadOnlyKeyValueStore<String, List<Span>> traceStore;
    final String traceId;

    GetTraceCall(
        ReadOnlyKeyValueStore<String, List<Span>> traceStore,
        String traceId) {
      this.traceStore = traceStore;
      this.traceId = traceId;
    }

    @Override
    List<Span> query() {
      try {
        final List<Span> spans = traceStore.get(traceId);
        if (spans == null) return new ArrayList<>();
        return spans;
      } catch (Exception e) {
        LOG.error("Error getting trace with ID {}", traceId, e);
        return null;
      }
    }

    @Override
    public Call<List<Span>> clone() {
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

    @Override
    List<DependencyLink> query() {
      try {
        long from = endTs - loopback;
        List<DependencyLink> dependencyLinks = new LinkedList<>();
        dependenciesStore.fetchAll(Instant.ofEpochMilli(from), Instant.ofEpochMilli(endTs))
            .forEachRemaining(keyValue -> dependencyLinks.add(keyValue.value));

        LOG.info("Dependencies found from={}-to={}: {}", from, endTs, dependencyLinks.size());

        return DependencyLinker.merge(dependencyLinks);
      } catch (Exception e) {
        LOG.error("Error looking up for dependencies", e);
        return new ArrayList<>();
      }
    }

    @Override
    public Call<List<DependencyLink>> clone() {
      return new GetDependenciesCall(endTs, loopback, dependenciesStore);
    }
  }

  abstract static class KafkaStreamsStoreCall<T> extends Call.Base<T> {

    KafkaStreamsStoreCall() {
    }

    @Override
    protected T doExecute() throws IOException {
      try {
        return query();
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    protected void doEnqueue(Callback<T> callback) {
      try {
        callback.onSuccess(query());
      } catch (Exception e) {
        callback.onError(e);
      }
    }

    abstract T query();
  }
}
