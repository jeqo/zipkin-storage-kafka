package zipkin2.storage.kafka;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.Call;
import zipkin2.storage.AutocompleteTags;
import zipkin2.storage.kafka.internal.KafkaStreamsStoreCall;
import zipkin2.storage.kafka.streams.TraceStoreSupplier;

import static zipkin2.storage.kafka.streams.TraceStoreSupplier.AUTOCOMPLETE_TAGS_STORE_NAME;

public class KafkaAutocompleteTags implements AutocompleteTags {
  static final Logger LOG = LoggerFactory.getLogger(TraceStoreSupplier.class);

  final KafkaStreams traceStoreStream;

  KafkaAutocompleteTags(KafkaStorage storage) {
    traceStoreStream = storage.getTraceStoreStream();
  }

  @Override public Call<List<String>> getKeys() {
    try {
      ReadOnlyKeyValueStore<String, Set<String>> autocompleteTagsStore =
          traceStoreStream.store(AUTOCOMPLETE_TAGS_STORE_NAME,
              QueryableStoreTypes.keyValueStore());
      return new GetKeysCall(autocompleteTagsStore);
    } catch (Exception e) {
      LOG.error("Error getting autocomplete keys", e);
      return Call.emptyList();
    }
  }

  @Override public Call<List<String>> getValues(String key) {
    return null;
  }

  static class GetKeysCall extends KafkaStreamsStoreCall<List<String>> {
    final ReadOnlyKeyValueStore<String, Set<String>> autocompleteTagsStore;

    GetKeysCall(ReadOnlyKeyValueStore<String, Set<String>> autocompleteTagsStore) {
      this.autocompleteTagsStore = autocompleteTagsStore;
    }

    @Override protected List<String> query() {
      try {
        List<String> keys = new ArrayList<>();
        autocompleteTagsStore.all().forEachRemaining(keyValue -> keys.add(keyValue.key));
        Collections.sort(keys); // comply with Zipkin API
        return keys;
      } catch (Exception e) {
        LOG.error("Error looking up autocomplete tag keys", e);
        return new ArrayList<>();
      }
    }

    @Override public Call<List<String>> clone() {
      return new GetKeysCall(autocompleteTagsStore);
    }
  }

  static class GetValuesCall extends KafkaStreamsStoreCall<List<String>> {
    final ReadOnlyKeyValueStore<String, Set<String>> autocompleteTagsStore;
    final String key;

    GetValuesCall(
        ReadOnlyKeyValueStore<String, Set<String>> autocompleteTagsStore, String key) {
      this.autocompleteTagsStore = autocompleteTagsStore;
      this.key = key;
    }

    @Override protected List<String> query() {
      try {
        List<String> values = new ArrayList<>(autocompleteTagsStore.get(key));
        Collections.sort(values); // comply with Zipkin API
        return values;
      } catch (Exception e) {
        LOG.error("Error looking up autocomplete tag values for key {}", key, e);
        return new ArrayList<>();
      }
    }

    @Override public Call<List<String>> clone() {
      return null;
    }
  }
}
