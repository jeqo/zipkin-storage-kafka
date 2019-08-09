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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.ConsumerRecordFactory;
import org.apache.kafka.streams.test.OutputVerifier;
import org.junit.jupiter.api.Test;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.storage.kafka.streams.serdes.DependencyLinkSerde;
import zipkin2.storage.kafka.streams.serdes.SpanSerde;
import zipkin2.storage.kafka.streams.serdes.SpansSerde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TraceAggregationSupplierTest {

  @Test void should_aggregate_spans_and_map_dependencies() {
    // Given: configuration
    String spansTopicName = "spans";
    String tracesTopicName = "traces";
    String dependencyLinksTopicName = "dependency-links";
    Duration traceInactivityGap = Duration.ofSeconds(1);
    SpanSerde spanSerde = new SpanSerde();
    SpansSerde spansSerde = new SpansSerde();
    DependencyLinkSerde dependencyLinkSerde = new DependencyLinkSerde();
    // When: topology built
    Topology topology = new TraceAggregationSupplier(
        spansTopicName, tracesTopicName, dependencyLinksTopicName, traceInactivityGap).get();
    TopologyDescription description = topology.describe();
    System.out.println("Topology: \n" + description);
    // Then: single threaded topology
    assertEquals(1, description.subtopologies().size());
    // Given: test driver
    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:1234");
    TopologyTestDriver testDriver = new TopologyTestDriver(topology, props);
    // When: two related spans coming on the same Session window
    ConsumerRecordFactory<String, Span> factory =
        new ConsumerRecordFactory<>(spansTopicName, new StringSerializer(), spanSerde.serializer());
    Span a = Span.newBuilder().traceId("a").id("a").name("op_a").kind(Span.Kind.CLIENT)
        .localEndpoint(Endpoint.newBuilder().serviceName("svc_a").build())
        .build();
    Span b = Span.newBuilder().traceId("a").id("b").name("op_b").kind(Span.Kind.SERVER)
        .localEndpoint(Endpoint.newBuilder().serviceName("svc_b").build())
        .build();
    testDriver.pipeInput(factory.create(spansTopicName, a.traceId(), a, 0L));
    testDriver.pipeInput(factory.create(spansTopicName, b.traceId(), b, 0L));
    // When: and new record arrive, moving the event clock further than inactivity gap
    Span c = Span.newBuilder().traceId("c").id("c").build();
    testDriver.pipeInput(factory.create(spansTopicName, c.traceId(), c, traceInactivityGap.toMillis() + 1));
    // Then: a trace is aggregated.
    ProducerRecord<String, List<Span>> trace =
        testDriver.readOutput(tracesTopicName, new StringDeserializer(), spansSerde.deserializer());
    assertNotNull(trace);
    OutputVerifier.compareKeyValue(trace, a.traceId(), Arrays.asList(a, b));
    // Then: a dependency link is created
    ProducerRecord<String, DependencyLink> linkRecord =
        testDriver.readOutput(dependencyLinksTopicName, new StringDeserializer(),
            dependencyLinkSerde.deserializer());
    assertNotNull(linkRecord);
    DependencyLink link = DependencyLink.newBuilder()
        .parent("svc_a").child("svc_b").callCount(1).errorCount(0)
        .build();
    OutputVerifier.compareKeyValue(linkRecord, "svc_a:svc_b", link);
  }

}