/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.NotificationCreationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refinery notices end to end against Postgres (REQ-REFINERY-023, -024): the ready producer
 * announces an order once and skips the ones that are not due, and the seeded rules reach the owner
 * and the member the yield was booked onto but never the acting member. Runs in a rolled-back
 * transaction.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class RefineryNoticeIntegrationTest {

  private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-4444444480c1");
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-4444444480c2");
  private static final ActorRef ACTING = new ActorRef(ACTOR, "Ada");

  @Autowired private RefineryReadyNoticeProducer producer;
  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ApplicationEvents events;
  @PersistenceContext private EntityManager entityManager;

  private User owner;
  private Location location;
  private Material material;

  @BeforeEach
  void seed() {
    owner = user(OWNER, "refinery-owner");
    user(ACTOR, "refinery-actor");
    location = new Location();
    location.setName("Refinery-Hub-" + UUID.randomUUID());
    location = locationRepository.save(location);
    material = new Material();
    material.setName("Refinery-Ore-" + UUID.randomUUID());
    material.setType(MaterialType.RAW);
    material = materialRepository.save(material);
  }

  private User user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user);
  }

  private RefineryOrder order(Instant startedAt, long minutes, RefineryOrderStatus status) {
    RefineryOrder order = new RefineryOrder();
    order.setOwner(owner);
    order.setLocation(location);
    order.setStatus(status);
    order.setStartedAt(startedAt);
    order.setDurationMinutes(minutes);
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(material);
    good.setInputQuantity(100);
    good.setOutputMaterial(material);
    good.setOutputQuantity(250);
    good.setQuality(500);
    good.setRefineryOrder(order);
    order.getGoods().add(good);
    RefineryOrder saved = refineryOrderRepository.saveAndFlush(order);
    return saved;
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  private List<NoticeEvent> readyEventsOf(RefineryOrder order) {
    return events.stream(NoticeEvent.class)
        .filter(e -> e.eventType() == NotificationEventType.REFINERY_ORDER_READY)
        .filter(e -> order.getId().equals(e.entityId()))
        .toList();
  }

  @Test
  void anOrderWhoseRunHasEndedIsAnnouncedOnceWithItsOutput() {
    RefineryOrder due =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.OPEN);

    assertThat(producer.produce(Instant.now())).isEqualTo(1);
    assertThat(producer.produce(Instant.now())).isZero();

    List<NoticeEvent> raised = readyEventsOf(due);
    assertThat(raised).hasSize(1);
    assertThat(raised.getFirst().contextRecipientUserId()).isEqualTo(OWNER);
    assertThat(raised.getFirst().renderParams())
        .containsEntry("location", location.getName())
        .containsEntry("outputs", "2.5 SCU " + material.getName());
    entityManager.clear();
    assertThat(refineryOrderRepository.findById(due.getId()).orElseThrow().getReadyNotifiedAt())
        .isNotNull();
  }

  @Test
  void anInProgressOrderWhoseRunHasEndedIsAnnounced() {
    RefineryOrder due =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.IN_PROGRESS);

    assertThat(producer.produce(Instant.now())).isEqualTo(1);

    assertThat(readyEventsOf(due)).hasSize(1);
  }

  @Test
  void anOrderStillRunningOrAlreadyStoredOrCanceledIsNotAnnounced() {
    RefineryOrder running =
        order(Instant.now().minus(10, ChronoUnit.MINUTES), 600, RefineryOrderStatus.OPEN);
    RefineryOrder canceled =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.CANCELED);
    RefineryOrder stored =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.OPEN);
    stored.setStoredAt(Instant.now());
    refineryOrderRepository.saveAndFlush(stored);

    assertThat(producer.produce(Instant.now())).isZero();

    assertThat(readyEventsOf(running)).isEmpty();
    assertThat(readyEventsOf(canceled)).isEmpty();
    assertThat(readyEventsOf(stored)).isEmpty();
  }

  @Test
  void anOrderWithNoRunTimeIsNeverAnnounced() {
    RefineryOrder open =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.OPEN);
    open.setDurationMinutes(null);
    refineryOrderRepository.saveAndFlush(open);

    assertThat(producer.produce(Instant.now())).isZero();
  }

  @Test
  void aNewRunTimeMakesTheOrderAnnounceableAgain() {
    RefineryOrder due =
        order(Instant.now().minus(3, ChronoUnit.HOURS), 60, RefineryOrderStatus.OPEN);
    assertThat(producer.produce(Instant.now())).isEqualTo(1);

    due.setReadyNotifiedAt(null);
    refineryOrderRepository.saveAndFlush(due);

    assertThat(producer.produce(Instant.now())).isEqualTo(1);
  }

  @Test
  void theReadyNoticeReachesTheOwnerAndStoringClearsIt() {
    UUID orderId = UUID.randomUUID();
    creationService.createFromEvent(RefineryNotices.ready(orderId, OWNER, "ARC-L1", "5 SCU Ore"));
    assertThat(inbox(OWNER)).containsExactly(NotificationType.REFINERY_ORDER_READY);

    creationService.createFromEvent(RefineryNotices.readyCleared(orderId));

    assertThat(inbox(OWNER)).isEmpty();
  }

  @Test
  void aChangeBySomebodyElseReachesTheRecipientButNotTheActor() {
    UUID orderId = UUID.randomUUID();

    creationService.createFromEvent(
        RefineryNotices.changedByOther(orderId, OWNER, "ARC-L1", "CANCELED", ACTING));

    assertThat(inbox(OWNER)).containsExactly(NotificationType.REFINERY_ORDER_CHANGED_BY_OTHER);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aChangeNamingTheActorAsRecipientTellsNobody() {
    UUID orderId = UUID.randomUUID();

    creationService.createFromEvent(
        RefineryNotices.changedByOther(orderId, ACTOR, "ARC-L1", "STORED", ACTING));

    assertThat(inbox(ACTOR)).isEmpty();
  }
}
