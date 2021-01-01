/*
 * Copyright 2019-2020 The OpenZipkin Authors
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
package zipkin2.module.storage.kafka;

import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.storage.StorageComponent;
import zipkin2.storage.kafka.KafkaStorage;

import static zipkin2.storage.kafka.KafkaStorage.HTTP_PATH_PREFIX;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ZipkinKafkaStorageProperties.class)
@ConditionalOnProperty(name = "zipkin.storage.type", havingValue = "kafka")
@ConditionalOnMissingBean(StorageComponent.class)
class ZipkinKafkaStorageModule {

  @ConditionalOnMissingBean @Bean StorageComponent storage(
    @Value("${zipkin.storage.search-enabled:true}") boolean searchEnabled,
    @Value("${zipkin.storage.autocomplete-keys:}") List<String> autocompleteKeys,
    @Value("${server.port:9411}") int port,
    ZipkinKafkaStorageProperties properties) {
    return properties.toBuilder()
      .searchEnabled(searchEnabled)
      .autocompleteKeys(autocompleteKeys)
      .serverPort(port)
      .build();
  }

  //TODO replace when Armeria supports Consumer<ServerBuilder> #61
  //@Bean public Consumer<ServerBuilder> storageHttpService(StorageComponent storage) {
  @Bean public ArmeriaServerConfigurator storageHttpService(StorageComponent storage) {
    return sb -> sb.annotatedService(HTTP_PATH_PREFIX, ((KafkaStorage) storage).httpService());
  }
}
