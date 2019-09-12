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

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;
import org.apache.kafka.streams.KafkaStreams;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.DependencyLink;
import zipkin2.Span;
import zipkin2.codec.DependencyLinkBytesDecoder;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.storage.QueryRequest;
import zipkin2.storage.ServiceAndSpanNames;
import zipkin2.storage.SpanStore;
import zipkin2.storage.kafka.internal.KafkaStoreScatterGatherListCall;
import zipkin2.storage.kafka.internal.KafkaStoreSingleKeyListCall;
import zipkin2.storage.kafka.streams.DependencyStoreTopologySupplier;
import zipkin2.storage.kafka.streams.TraceStoreTopologySupplier;

import static zipkin2.storage.kafka.streams.DependencyStoreTopologySupplier.DEPENDENCIES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.REMOTE_SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.SERVICE_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.SPAN_NAMES_STORE_NAME;
import static zipkin2.storage.kafka.streams.TraceStoreTopologySupplier.TRACES_STORE_NAME;

/**
 * Span store backed by Kafka Stream distributed state stores built by {@link
 * TraceStoreTopologySupplier} and {@link DependencyStoreTopologySupplier}, and made accessible by
 * {@link  KafkaStoreHttpService}.
 */
public class KafkaSpanStore implements SpanStore, ServiceAndSpanNames {
  // Kafka Streams Store provider
  final KafkaStreams traceStoreStream;
  final KafkaStreams dependencyStoreStream;
  final BiFunction<String, Integer, String> httpBaseUrl;

  KafkaSpanStore(KafkaStorage storage) {
    traceStoreStream = storage.getTraceStoreStream();
    dependencyStoreStream = storage.getDependencyStoreStream();
    httpBaseUrl = storage.httpBaseUrl;
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    return new GetTracesCall(traceStoreStream, httpBaseUrl, request);
  }

  @Override
  public Call<List<Span>> getTrace(String traceId) {
    return new GetTraceCall(traceStoreStream, httpBaseUrl, traceId);
  }

  @Deprecated @Override public Call<List<String>> getServiceNames() {
    return new GetServiceNamesCall(traceStoreStream, httpBaseUrl);
  }

  @Deprecated @Override public Call<List<String>> getSpanNames(String serviceName) {
    return new GetSpanNamesCall(traceStoreStream, serviceName, httpBaseUrl);
  }

  @Override public Call<List<String>> getRemoteServiceNames(String serviceName) {
    return new GetRemoteServiceNamesCall(traceStoreStream, serviceName, httpBaseUrl);
  }

  @Override public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    return new GetDependenciesCall(dependencyStoreStream, httpBaseUrl, endTs, lookback);
  }

  static class GetServiceNamesCall extends KafkaStoreScatterGatherListCall<String> {
    final KafkaStreams traceStoreStream;
    final BiFunction<String, Integer, String> httpBaseUrl;

    GetServiceNamesCall(KafkaStreams traceStoreStream,
        BiFunction<String, Integer, String> httpBaseUrl) {
      super(traceStoreStream, SERVICE_NAMES_STORE_NAME, httpBaseUrl, "/serviceNames");
      this.traceStoreStream = traceStoreStream;
      this.httpBaseUrl = httpBaseUrl;
    }

    @Override protected String parse(JsonNode node) {
      return node.textValue();
    }

    @Override public Call<List<String>> clone() {
      return new GetServiceNamesCall(traceStoreStream, httpBaseUrl);
    }
  }

  static class GetSpanNamesCall extends KafkaStoreSingleKeyListCall<String> {
    final KafkaStreams traceStoreStream;
    final String serviceName;
    final BiFunction<String, Integer, String> httpBaseUrl;

    GetSpanNamesCall(KafkaStreams traceStoreStream, String serviceName,
        BiFunction<String, Integer, String> httpBaseUrl) {
      super(traceStoreStream, SPAN_NAMES_STORE_NAME, httpBaseUrl,
          "/serviceNames/" + serviceName + "/spanNames", serviceName);
      this.traceStoreStream = traceStoreStream;
      this.serviceName = serviceName;
      this.httpBaseUrl = httpBaseUrl;
    }

    @Override protected String parse(JsonNode node) {
      return node.textValue();
    }

    @Override protected void doEnqueue(Callback<List<String>> callback) {
      try {
        callback.onSuccess(doExecute());
      } catch (IOException e) {
        callback.onError(e);
      }
    }

    @Override public Call<List<String>> clone() {
      return new GetSpanNamesCall(traceStoreStream, serviceName, httpBaseUrl);
    }
  }

  static class GetRemoteServiceNamesCall extends KafkaStoreSingleKeyListCall<String> {
    final KafkaStreams traceStoreStream;
    final String serviceName;
    final BiFunction<String, Integer, String> httpBaseUrl;

    GetRemoteServiceNamesCall(KafkaStreams traceStoreStream, String serviceName,
        BiFunction<String, Integer, String> httpBaseUrl) {
      super(traceStoreStream, REMOTE_SERVICE_NAMES_STORE_NAME, httpBaseUrl,
          "/serviceNames/" + serviceName + "/remoteServiceNames", serviceName);
      this.traceStoreStream = traceStoreStream;
      this.serviceName = serviceName;
      this.httpBaseUrl = httpBaseUrl;
    }

    @Override protected String parse(JsonNode node) {
      return node.textValue();
    }

    @Override public Call<List<String>> clone() {
      return new GetRemoteServiceNamesCall(traceStoreStream, serviceName, httpBaseUrl);
    }
  }

  static class GetTracesCall extends KafkaStoreScatterGatherListCall<List<Span>> {
    final KafkaStreams traceStoreStream;
    final BiFunction<String, Integer, String> httpBaseUrl;
    final QueryRequest request;

    GetTracesCall(KafkaStreams traceStoreStream,
        BiFunction<String, Integer, String> httpBaseUrl,
        QueryRequest request) {
      super(traceStoreStream, TRACES_STORE_NAME, httpBaseUrl,
          ("/traces?"
              + (request.serviceName() == null ? "" : "serviceName=" + request.serviceName() + "&")
              + (request.remoteServiceName() == null ? ""
              : "remoteServiceName=" + request.remoteServiceName() + "&")
              + (request.spanName() == null ? "" : "spanName=" + request.spanName() + "&")
              + (request.annotationQueryString() == null ? ""
              : "annotationQuery=" + request.annotationQueryString() + "&")
              + (request.minDuration() == null ? "" : "minDuration=" + request.minDuration() + "&")
              + (request.maxDuration() == null ? "" : "maxDuration=" + request.maxDuration() + "&")
              + ("endTs=" + request.endTs() + "&")
              + ("lookback=" + request.lookback() + "&")
              + ("limit=" + request.limit())));
      this.traceStoreStream = traceStoreStream;
      this.httpBaseUrl = httpBaseUrl;
      this.request = request;
    }

    @Override protected List<Span> parse(JsonNode node) {
      return SpanBytesDecoder.JSON_V2.decodeList(node.toString().getBytes());
    }

    @Override protected void doEnqueue(Callback<List<List<Span>>> callback) {
      try {
        callback.onSuccess(doExecute());
      } catch (IOException e) {
        callback.onError(e);
      }
    }

    @Override public Call<List<List<Span>>> clone() {
      return new GetTracesCall(traceStoreStream, httpBaseUrl, request);
    }
  }

  static class GetTraceCall extends KafkaStoreSingleKeyListCall<Span> {
    final KafkaStreams traceStoreStream;
    final BiFunction<String, Integer, String> httpBaseUrl;
    final String traceId;

    GetTraceCall(KafkaStreams traceStoreStream,
        BiFunction<String, Integer, String> httpBaseUrl,
        String traceId) {
      super(traceStoreStream, TRACES_STORE_NAME, httpBaseUrl, String.format("/traces/%s", traceId),
          traceId);
      this.traceStoreStream = traceStoreStream;
      this.httpBaseUrl = httpBaseUrl;
      this.traceId = traceId;
    }

    @Override protected Span parse(JsonNode node) {
      return SpanBytesDecoder.JSON_V2.decodeOne(node.toString().getBytes());
    }

    @Override public Call<List<Span>> clone() {
      return new GetTraceCall(traceStoreStream, httpBaseUrl, traceId);
    }
  }

  static class GetDependenciesCall extends KafkaStoreScatterGatherListCall<DependencyLink> {
    final KafkaStreams dependencyStoreStream;
    final BiFunction<String, Integer, String> httpBaseUrl;
    final long endTs, lookback;

    GetDependenciesCall(KafkaStreams dependencyStoreStream,
        BiFunction<String, Integer, String> httpBaseUrl,
        long endTs, long lookback) {
      super(dependencyStoreStream, DEPENDENCIES_STORE_NAME,
          httpBaseUrl, "/dependencies?endTs=" + endTs + "&lookback=" + lookback);
      this.dependencyStoreStream = dependencyStoreStream;
      this.httpBaseUrl = httpBaseUrl;
      this.endTs = endTs;
      this.lookback = lookback;
    }

    @Override protected DependencyLink parse(JsonNode node) {
      return DependencyLinkBytesDecoder.JSON_V1.decodeOne(node.toString().getBytes());
    }

    @Override public Call<List<DependencyLink>> clone() {
      return new GetDependenciesCall(dependencyStoreStream, httpBaseUrl, endTs, lookback);
    }
  }
}
