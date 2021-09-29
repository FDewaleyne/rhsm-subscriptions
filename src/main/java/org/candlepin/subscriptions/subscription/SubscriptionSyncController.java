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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.candlepin.subscriptions.capacity.CapacityReconciliationController;
import org.candlepin.subscriptions.capacity.files.ProductWhitelist;
import org.candlepin.subscriptions.db.OfferingRepository;
import org.candlepin.subscriptions.db.SubscriptionRepository;
import org.candlepin.subscriptions.db.model.OrgConfigRepository;
import org.candlepin.subscriptions.exception.ErrorCode;
import org.candlepin.subscriptions.exception.ExternalServiceException;
import org.candlepin.subscriptions.subscription.api.model.Subscription;
import org.candlepin.subscriptions.subscription.api.model.SubscriptionProduct;
import org.candlepin.subscriptions.task.TaskQueueProperties;
import org.candlepin.subscriptions.util.ApplicationClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Update subscriptions from subscription service responses. */
@Component
@Slf4j
public class SubscriptionSyncController {
  private static final int SUBS_PAGE_SIZE = 100;

  private SubscriptionRepository subscriptionRepository;
  private OrgConfigRepository orgRepository;
  private OfferingRepository offeringRepository;
  private SubscriptionService subscriptionService;
  private ApplicationClock clock;
  private CapacityReconciliationController capacityReconciliationController;
  private Timer syncTimer;
  private Timer enqueueAllTimer;
  private KafkaTemplate<String, SyncSubscriptionsTask> syncSubscriptionsByOrgKafkaTemplate;
  private String syncSubscriptionsTopic;
  private final ProductWhitelist productWhitelist;

  @Autowired
  public SubscriptionSyncController(
      SubscriptionRepository subscriptionRepository,
      OrgConfigRepository orgRepository,
      OfferingRepository offeringRepository,
      ApplicationClock clock,
      SubscriptionService subscriptionService,
      CapacityReconciliationController capacityReconciliationController,
      MeterRegistry meterRegistry,
      KafkaTemplate<String, SyncSubscriptionsTask> syncSubscriptionsByOrgKafkaTemplate,
      ProductWhitelist productWhitelist,
      @Qualifier("syncSubscriptionTasks") TaskQueueProperties props) {
    this.subscriptionRepository = subscriptionRepository;
    this.orgRepository = orgRepository;
    this.offeringRepository = offeringRepository;
    this.subscriptionService = subscriptionService;
    this.capacityReconciliationController = capacityReconciliationController;
    this.clock = clock;
    this.syncTimer = meterRegistry.timer("swatch_subscription_sync_page");
    this.enqueueAllTimer = meterRegistry.timer("swatch_subscription_sync_enqueue_all");
    this.productWhitelist = productWhitelist;
    this.syncSubscriptionsTopic = props.getTopic();
    this.syncSubscriptionsByOrgKafkaTemplate = syncSubscriptionsByOrgKafkaTemplate;
  }

  @Transactional
  public void syncSubscription(Subscription subscription) {
    String sku = sku(subscription);

    if (!productWhitelist.productIdMatches(sku)) {
      log.info(
          "Sku {} not on allowlist, skipping subscription sync for subscriptionId: {} in org: {} ",
          sku,
          subscription.getId(),
          subscription.getWebCustomerId());
      return;
    }

    if (!offeringRepository.existsById(sku)) {
      log.info(
          "Sku={} not in Offering repository, skipping subscription sync for subscriptionId={} in org={}",
          sku,
          subscription.getId(),
          subscription.getWebCustomerId());
      return;
    }

    log.debug("Syncing subscription from external service={}", subscription);
    // TODO: https://issues.redhat.com/browse/ENT-4029 //NOSONAR
    final Optional<org.candlepin.subscriptions.db.model.Subscription> subscriptionOptional =
        subscriptionRepository.findActiveSubscription(String.valueOf(subscription.getId()));

    final org.candlepin.subscriptions.db.model.Subscription newOrUpdated = convertDto(subscription);
    log.debug("New subscription that will need to be saved={}", newOrUpdated);

    if (subscriptionOptional.isPresent()) {
      final org.candlepin.subscriptions.db.model.Subscription existingSubscription =
          subscriptionOptional.get();
      log.debug("Existing subscription in DB={}", existingSubscription);
      if (!existingSubscription.equals(newOrUpdated)) {
        if (existingSubscription.quantityHasChanged(newOrUpdated.getQuantity())) {
          existingSubscription.endSubscription();
          subscriptionRepository.save(existingSubscription);
          final org.candlepin.subscriptions.db.model.Subscription newSub =
              org.candlepin.subscriptions.db.model.Subscription.builder()
                  .subscriptionId(existingSubscription.getSubscriptionId())
                  .sku(existingSubscription.getSku())
                  .ownerId(existingSubscription.getOwnerId())
                  .accountNumber(existingSubscription.getAccountNumber())
                  .quantity(subscription.getQuantity())
                  .startDate(OffsetDateTime.now())
                  .endDate(clock.dateFromMilliseconds(subscription.getEffectiveEndDate()))
                  .marketplaceSubscriptionId(SubscriptionDtoUtil.extractMarketplaceId(subscription))
                  .subscriptionNumber(subscription.getSubscriptionNumber())
                  .build();
          subscriptionRepository.save(newSub);
        } else {
          updateSubscription(subscription, existingSubscription);
          subscriptionRepository.save(existingSubscription);
        }
        capacityReconciliationController.reconcileCapacityForSubscription(newOrUpdated);
      }
    } else {
      subscriptionRepository.save(newOrUpdated);
      capacityReconciliationController.reconcileCapacityForSubscription(newOrUpdated);
    }
  }

  @Transactional
  public void syncSubscription(String subscriptionId) {
    Subscription subscription = subscriptionService.getSubscriptionById(subscriptionId);
    syncSubscription(subscription);
  }

  void syncSubscriptions(String orgId, int offset, int limit) {
    log.info("Syncing subscriptions for org={} with offset={} and limit={} ", orgId, offset, limit);
    Timer.Sample syncTime = Timer.start();

    int pageSize = limit + 1;
    List<Subscription> subscriptions =
        subscriptionService.getSubscriptionsByOrgId(orgId, offset, pageSize);
    log.info(
        "Fetched subscriptions of size={} for org={} from external service ",
        subscriptions.size(),
        orgId);

    boolean hasMore = subscriptions.size() >= pageSize;
    subscriptions.forEach(this::syncSubscription);
    if (hasMore) {
      enqueueSubscriptionSync(orgId, offset + limit, limit);
    }
    Duration syncDuration = Duration.ofNanos(syncTime.stop(syncTimer));
    log.info(
        "Fetched and synced numSubs={} for orgId={} offset={} limit={} in subSyncedTimeMillis={}",
        subscriptions.size(),
        orgId,
        offset,
        limit,
        syncDuration.toMillis());
  }

  private void enqueueSubscriptionSync(String orgId, int offset, int limit) {
    log.debug("Enqueuing subscription sync for orgId={} offset={} limit={}", orgId, offset, limit);
    syncSubscriptionsByOrgKafkaTemplate.send(
        syncSubscriptionsTopic,
        SyncSubscriptionsTask.builder().orgId(orgId).offset(offset).limit(limit).build());
  }

  @Transactional
  public void syncAllSubcriptionsForOrg(String orgId) {
    syncSubscriptions(orgId, 0, SUBS_PAGE_SIZE);
  }

  /**
   * Enqueues all enrolled organizations to sync their subscriptions with the upstream subscription
   * service.
   */
  @Transactional
  public void syncAllSubscriptionsForAllOrgs() {
    Timer.Sample enqueueAllTime = Timer.start();
    orgRepository
        .findSyncEnabledOrgs()
        .forEach(orgId -> enqueueSubscriptionSync(orgId, 0, SUBS_PAGE_SIZE));
    Duration enqueueAllDuration = Duration.ofNanos(enqueueAllTime.stop(enqueueAllTimer));
    log.info(
        "Enqueued orgs to sync subscriptions from upstream in enqueueTimeMillis={}",
        enqueueAllDuration.toMillis());
  }

  private org.candlepin.subscriptions.db.model.Subscription convertDto(Subscription subscription) {

    return org.candlepin.subscriptions.db.model.Subscription.builder()
        .subscriptionId(String.valueOf(subscription.getId()))
        .subscriptionNumber(subscription.getSubscriptionNumber())
        .sku(SubscriptionDtoUtil.extractSku(subscription))
        .ownerId(subscription.getWebCustomerId().toString())
        .accountNumber(String.valueOf(subscription.getOracleAccountNumber()))
        .quantity(subscription.getQuantity())
        .startDate(clock.dateFromMilliseconds(subscription.getEffectiveStartDate()))
        .endDate(clock.dateFromMilliseconds(subscription.getEffectiveEndDate()))
        .marketplaceSubscriptionId(SubscriptionDtoUtil.extractMarketplaceId(subscription))
        .build();
  }

  protected void updateSubscription(
      Subscription dto, org.candlepin.subscriptions.db.model.Subscription entity) {
    if (dto.getEffectiveEndDate() != null) {
      entity.setEndDate(clock.dateFromMilliseconds(dto.getEffectiveEndDate()));
    }
  }

  private String sku(Subscription subscription) {
    return subscription.getSubscriptionProducts().stream()
        .filter(
            subscriptionProduct ->
                Objects.isNull(subscriptionProduct.getParentSubscriptionProductId()))
        .map(SubscriptionProduct::getSku)
        .findFirst()
        .orElseThrow(
            () ->
                new ExternalServiceException(
                    ErrorCode.SUBSCRIPTION_SERVICE_REQUEST_ERROR,
                    "Sku not present on subscription with id " + subscription.getId(),
                    null));
  }
}
