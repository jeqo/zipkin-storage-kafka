/*
 * Copyright 2019 The OpenZipkin Authors
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

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.Span;
import zipkin2.codec.SpanBytesEncoder;
import zipkin2.internal.AggregateCall;
import zipkin2.storage.GroupByTraceId;
import zipkin2.storage.SpanConsumer;
import zipkin2.storage.kafka.internal.AwaitableCallback;

/**
 * Span Consumer to compensate current {@code zipkin2.reporter.kafka.KafkaSender} distribution of
 * span batched without key.
 * <p>
 * This component split batch into individual spans keyed by trace ID to enabled downstream
 * processing of spans as part of a trace.
 */
final class KafkaSpanConsumer implements SpanConsumer {
  // Topic names
  final String spansTopicName;
  // Kafka producers
  final Producer<String, byte[]> producer;

  KafkaSpanConsumer(KafkaStorage storage) {
    spansTopicName = storage.partitioningSpansTopic;
    producer = storage.getProducer();
  }

  @Override public Call<Void> accept(List<Span> spans) {
    if (spans.isEmpty()) return Call.create(null);
    List<List<Span>> groupedByTraceId = GroupByTraceId.create(true).map(spans);
    List<Call<Void>> calls = new ArrayList<>();
    for (List<Span> grouped : groupedByTraceId) {
      if (!grouped.isEmpty()) {
        byte[] value = SpanBytesEncoder.PROTO3.encodeList(grouped);
        String traceId = grouped.get(0).traceId();
        calls.add(KafkaProducerCall.create(producer, spansTopicName, traceId, value));
      }
    }
    return AggregateCall.newVoidCall(calls);
  }

  static class KafkaProducerCall extends Call.Base<Void> {
    final Producer<String, byte[]> kafkaProducer;
    final String topic;
    final String key;
    final byte[] value;

    KafkaProducerCall(
        Producer<String, byte[]> kafkaProducer,
        String topic,
        String key,
        byte[] value) {
      this.kafkaProducer = kafkaProducer;
      this.topic = topic;
      this.key = key;
      this.value = value;
    }

    static Call<Void> create(
        Producer<String, byte[]> producer,
        String topic,
        String key,
        byte[] value) {
      return new KafkaProducerCall(producer, topic, key, value);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected Void doExecute() {
      AwaitableCallback callback = new AwaitableCallback();
      kafkaProducer.send(new ProducerRecord<>(topic, key, value), new CallbackAdapter(callback));
      callback.await();
      return null;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    protected void doEnqueue(Callback<Void> callback) {
      kafkaProducer.send(new ProducerRecord<>(topic, key, value), new CallbackAdapter(callback));
    }

    @Override public Call<Void> clone() {
      return new KafkaProducerCall(kafkaProducer, topic, key, value);
    }

    static final class CallbackAdapter implements org.apache.kafka.clients.producer.Callback {
      final Callback<Void> delegate;

      CallbackAdapter(Callback<Void> delegate) {
        this.delegate = delegate;
      }

      @Override public void onCompletion(RecordMetadata metadata, Exception exception) {
        if (exception == null) {
          delegate.onSuccess(null);
        } else {
          delegate.onError(exception);
        }
      }

      @Override public String toString() {
        return delegate.toString();
      }
    }
  }
}
