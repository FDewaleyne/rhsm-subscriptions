/*
 * Copyright Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.candlepin.subscriptions.task.TaskQueueProperties;
import org.candlepin.subscriptions.umb.CanonicalMessage;
import org.candlepin.subscriptions.umb.UmbProperties;
import org.candlepin.subscriptions.umb.UmbSubscription;
import org.candlepin.subscriptions.util.KafkaConsumerRegistry;
import org.candlepin.subscriptions.util.SeekableKafkaConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jms.activemq.ActiveMQProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@Profile("capacity-ingress")
public class SubscriptionWorker extends SeekableKafkaConsumer {

  SubscriptionSyncController subscriptionSyncController;

  private final UmbProperties umbProperties;
  private final XmlMapper xmlMapper;

  protected SubscriptionWorker(
      @Qualifier("syncSubscriptionTasks") TaskQueueProperties taskQueueProperties,
      KafkaConsumerRegistry kafkaConsumerRegistry,
      SubscriptionSyncController subscriptionSyncController,
      UmbProperties umbProperties) {
    super(taskQueueProperties, kafkaConsumerRegistry);
    this.subscriptionSyncController = subscriptionSyncController;
    this.umbProperties = umbProperties;
    this.xmlMapper = CanonicalMessage.createMapper();
    if (!umbProperties.isProcessingEnabled()) {
      log.warn("Message processing disabled. Messages will be acked and ignored.");
    }
  }

  @KafkaListener(
      id = "#{__listener.groupId}",
      topics = "#{__listener.topic}",
      containerFactory = "subscriptionSyncListenerContainerFactory")
  public void receive(SyncSubscriptionsTask syncSubscriptionsTask) {
    log.info(
        "Subscription Worker is syncing subs with values: {} ", syncSubscriptionsTask.toString());
    subscriptionSyncController.syncSubscriptions(
        syncSubscriptionsTask.getOrgId(),
        syncSubscriptionsTask.getOffset(),
        syncSubscriptionsTask.getLimit());
  }

  @Bean
  @ConfigurationProperties(prefix = "spring.activemq")
  public ActiveMQProperties activeMQProperties() {
    return new ActiveMQProperties();
  }

  @Bean
  public ActiveMQSslConnectionFactory activeMQSslConnectionFactory(
      ActiveMQProperties activeMQProperties) throws Exception {
    ActiveMQSslConnectionFactory factory = new ActiveMQSslConnectionFactory();
    factory.setExceptionListener(e -> log.error("Exception thrown in ActiveMQ connection", e));
    if (StringUtils.hasText(activeMQProperties.getBrokerUrl())) {
      factory.setBrokerURL(activeMQProperties.getBrokerUrl());
      if (!umbProperties.providesTruststore() || !umbProperties.usesClientAuth()) {
        log.warn("UMB config requires keystore and truststore - not provided or not valid.");
      }
    } else {
      // default to an embedded broker
      factory.setBrokerURL("vm://localhost?broker.persistent=false");
    }
    if (umbProperties.providesTruststore()) {
      factory.setTrustStore(umbProperties.getTruststore().getFile().getAbsolutePath());
      factory.setTrustStorePassword(String.valueOf(umbProperties.getTruststorePassword()));
    }
    if (umbProperties.usesClientAuth()) {
      factory.setKeyStore(umbProperties.getKeystore().getFile().getAbsolutePath());
      factory.setKeyStorePassword(String.valueOf(umbProperties.getKeystorePassword()));
    }
    return factory;
  }

  @JmsListener(destination = "#{@umbProperties.subscriptionTopic}")
  public void receive(String subscriptionMessageXml) throws JsonProcessingException {
    log.debug("Received message from UMB {}", subscriptionMessageXml);
    if (!umbProperties.isProcessingEnabled()) {
      return;
    }
    CanonicalMessage subscriptionMessage =
        xmlMapper.readValue(subscriptionMessageXml, CanonicalMessage.class);
    UmbSubscription subscription = subscriptionMessage.getPayload().getSync().getSubscription();
    log.info(
        "Received UMB message for subscriptionNumber={} webCustomerId={}",
        subscription.getSubscriptionNumber(),
        subscription.getWebCustomerId());
    subscriptionSyncController.saveUmbSubscription(subscription);
  }
}
