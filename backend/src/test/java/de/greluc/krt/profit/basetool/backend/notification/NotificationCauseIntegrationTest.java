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

package de.greluc.krt.profit.basetool.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationDto;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationMuteService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationCreationService;
import de.greluc.krt.profit.basetool.backend.refinery.api.events.RefineryNotices;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Observes logical-cause delivery through committed events and the recipient's public Inbox. */
@SpringBootTest
class NotificationCauseIntegrationTest {
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private NotificationService notificationService;
  @Autowired private NotificationMuteService notificationMuteService;
  @Autowired private NotificationCreationService notificationCreationService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID recipient;

  @BeforeEach
  void seedRecipient() {
    recipient = UUID.randomUUID();
    User user = new User();
    user.setId(recipient);
    user.setUsername("cause-recipient-" + recipient);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    userRepository.saveAndFlush(user);
  }

  @AfterEach
  void removeRecipient() {
    notificationService.markAllRead(recipient);
    notificationService.deleteAllRead(recipient);
    userRepository.deleteById(recipient);
  }

  @Test
  void aCommittedLogicalCauseDeliversItsOriginalNotice() {
    UUID controlEntity = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ ->
            eventPublisher.publishEvent(
                RefineryNotices.ready(
                    controlEntity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox())
                .singleElement()
                .satisfies(notice -> assertThat(notice.entityId()).isEqualTo(controlEntity)));
    notificationService.markAllRead(recipient);
    notificationService.deleteAllRead(recipient);
    assertThat(inbox()).isEmpty();

    UUID entity = UUID.randomUUID();
    NotificationCause cause =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ -> eventPublisher.publishEvent(cause));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox())
                .singleElement()
                .satisfies(notice -> {
                  assertThat(notice.type()).isEqualTo(NotificationType.REFINERY_ORDER_READY.name());
                  assertThat(notice.entityId()).isEqualTo(entity);
                }));
  }

  @Test
  void clearingReadNoticesDoesNotForgetTheCauseButALaterRevisionRemainsDistinct() {
    UUID entity = UUID.randomUUID();
    NotificationCause first =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    assertThat(notificationCreationService.createFromCause(first)).containsExactly(recipient);
    UUID firstNotice = inbox().getFirst().id();
    notificationService.markAllRead(recipient);
    notificationService.deleteAllRead(recipient);
    assertThat(inbox()).isEmpty();

    assertThat(notificationCreationService.createFromCause(first)).isEmpty();
    assertThat(inbox()).isEmpty();

    NotificationCause later = new NotificationCause(UUID.randomUUID(), first.events());
    assertThat(notificationCreationService.createFromCause(later)).containsExactly(recipient);
    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.id()).isNotEqualTo(firstNotice);
          assertThat(notice.entityId()).isEqualTo(entity);
        });
  }

  @Test
  void replayingAnOldSupersessionCannotRemoveOrReplaceTheNewerPrompt() {
    UUID entity = UUID.randomUUID();
    NotificationCause first =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    assertThat(notificationCreationService.createFromCause(first)).containsExactly(recipient);
    UUID firstNotice = inbox().getFirst().id();
    NotificationCause later =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "2 SCU Test Ore")));
    assertThat(notificationCreationService.createFromCause(later)).containsExactly(recipient);
    UUID laterNotice = inbox().getFirst().id();
    assertThat(laterNotice).isNotEqualTo(firstNotice);

    assertThat(notificationCreationService.createFromCause(first)).isEmpty();
    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.id()).isEqualTo(laterNotice);
          assertThat(notice.params().get("outputs")).isEqualTo("2 SCU Test Ore");
        });
  }

  @Test
  void aFullyMutedCauseStaysProcessedWhenThePreferenceChanges() {
    UUID entity = UUID.randomUUID();
    NotificationCause first =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    notificationMuteService.setMuted(recipient, NotificationType.REFINERY_ORDER_READY, true);
    assertThat(notificationCreationService.createFromCause(first)).isEmpty();
    assertThat(inbox()).isEmpty();

    notificationMuteService.setMuted(recipient, NotificationType.REFINERY_ORDER_READY, false);
    assertThat(notificationCreationService.createFromCause(first)).isEmpty();
    assertThat(inbox()).isEmpty();

    NotificationCause later = new NotificationCause(UUID.randomUUID(), first.events());
    assertThat(notificationCreationService.createFromCause(later)).containsExactly(recipient);
    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> assertThat(notice.entityId()).isEqualTo(entity));
  }

  @Test
  void originalMutesAndEachEventsActorApplyBeforeCausePriority() {
    UUID entity = UUID.randomUUID();
    var otherActor = new ActorRef(UUID.randomUUID(), "Synthetic actor");
    var high =
        RefineryNotices.changedByOther(entity, recipient, "Synthetic Hub", "UPDATED", otherActor);
    var low = RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore");
    notificationMuteService.setMuted(
        recipient, NotificationType.REFINERY_ORDER_CHANGED_BY_OTHER, true);

    assertThat(
            notificationCreationService.createFromCause(
                new NotificationCause(UUID.randomUUID(), List.of(high, low))))
        .containsExactly(recipient);
    assertThat(inbox())
        .singleElement()
        .satisfies(notice ->
            assertThat(notice.type()).isEqualTo(NotificationType.REFINERY_ORDER_READY.name()));
    notificationService.markAllRead(recipient);
    notificationService.deleteAllRead(recipient);

    notificationMuteService.setMuted(
        recipient, NotificationType.REFINERY_ORDER_CHANGED_BY_OTHER, false);
    var self =
        RefineryNotices.changedByOther(
            entity,
            recipient,
            "Synthetic Hub",
            "UPDATED",
            new ActorRef(recipient, "Synthetic recipient"));
    assertThat(
            notificationCreationService.createFromCause(
                new NotificationCause(UUID.randomUUID(), List.of(self, low))))
        .containsExactly(recipient);
    assertThat(inbox())
        .singleElement()
        .satisfies(notice ->
            assertThat(notice.type()).isEqualTo(NotificationType.REFINERY_ORDER_READY.name()));
    notificationService.markAllRead(recipient);
    notificationService.deleteAllRead(recipient);

    assertThat(
            notificationCreationService.createFromCause(
                new NotificationCause(UUID.randomUUID(), List.of(high, low))))
        .containsExactly(recipient);
    assertThat(inbox())
        .singleElement()
        .satisfies(notice ->
            assertThat(notice.type())
                .isEqualTo(NotificationType.REFINERY_ORDER_CHANGED_BY_OTHER.name()));
  }

  @Test
  void reconciliationReplacesAnEntitledNoticeRemovedByAnotherChildsCleanup() {
    UUID entity = UUID.randomUUID();
    assertThat(
            notificationCreationService.createFromEvent(
                RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")))
        .containsExactly(recipient);
    UUID oldNotice = inbox().getFirst().id();
    var reconcile =
        NoticeEvent.builder()
            .eventType(NotificationEventType.REFINERY_ORDER_READY)
            .entityType(RefineryNotices.ENTITY_TYPE)
            .entityId(entity)
            .contextRecipientUserId(recipient)
            .renderParams(
                Map.of(
                    "order",
                    RefineryNotices.shortId(entity),
                    "location",
                    "Synthetic Hub",
                    "outputs",
                    "2 SCU Test Ore"))
            .resolvesNotificationTypes(Set.of(NotificationType.REFINERY_ORDER_READY))
            .reconcileRecipients(Set.of(recipient))
            .build();

    assertThat(
            notificationCreationService.createFromCause(
                new NotificationCause(
                    UUID.randomUUID(), List.of(reconcile, RefineryNotices.readyCleared(entity)))))
        .containsExactly(recipient);

    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.id()).isNotEqualTo(oldNotice);
          assertThat(notice.type()).isEqualTo(NotificationType.REFINERY_ORDER_READY.name());
          assertThat(notice.params().get("outputs")).isEqualTo("2 SCU Test Ore");
        });
  }

  @Test
  void simultaneousDeliveryWaitsForTheWinningCauseAndChangesTheInboxOnce() throws Exception {
    UUID entity = UUID.randomUUID();
    NotificationCause cause =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")));
    AtomicInteger contenderPid = new AtomicInteger();
    AtomicReference<Future<Set<UUID>>> contender = new AtomicReference<>();
    try (var worker = Executors.newSingleThreadExecutor()) {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(_ -> {
            Integer winnerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertThat(notificationCreationService.createFromCause(cause))
                .containsExactly(recipient);
            contender.set(
                worker.submit(() ->
                    new TransactionTemplate(transactionManager)
                        .execute(_ -> {
                          contenderPid.set(
                              jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                          return notificationCreationService.createFromCause(cause);
                        })));
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND"
                                + " wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))",
                            Integer.class,
                            contenderPid.get(),
                            winnerPid)
                        > 0);
          });
      assertThat(contender.get().get(10, TimeUnit.SECONDS)).isEmpty();
    }
    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.type()).isEqualTo(NotificationType.REFINERY_ORDER_READY.name());
          assertThat(notice.entityId()).isEqualTo(entity);
        });
  }

  @Test
  void failedCreationRollsBackCleanupAndAllowsTheSameCauseToBeDelivered() {
    UUID entity = UUID.randomUUID();
    assertThat(
            notificationCreationService.createFromEvent(
                RefineryNotices.ready(entity, recipient, "Synthetic Hub", "1 SCU Test Ore")))
        .containsExactly(recipient);
    UUID originalNotice = inbox().getFirst().id();
    NotificationCause cause =
        new NotificationCause(
            UUID.randomUUID(),
            List.of(RefineryNotices.ready(entity, recipient, "Synthetic Hub", "2 SCU Test Ore")));
    String fault = "notice_fault_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        """
        CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $fault$
        BEGIN
          IF NEW.entity_id = '%s'::uuid THEN
            RAISE EXCEPTION 'Synthetic notification insert failure';
          END IF;
          RETURN NEW;
        END;
        $fault$
        """
            .formatted(fault, entity));
    try {
      jdbc.execute(
          "CREATE TRIGGER "
              + fault
              + " BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION "
              + fault
              + "()");
      assertThatThrownBy(() -> notificationCreationService.createFromCause(cause))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS " + fault + " ON notification");
      jdbc.execute("DROP FUNCTION " + fault + "()");
    }
    assertThat(inbox())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.id()).isEqualTo(originalNotice);
          assertThat(notice.params().get("outputs")).isEqualTo("1 SCU Test Ore");
        });

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(_ -> eventPublisher.publishEvent(cause));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox())
                .singleElement()
                .satisfies(notice -> {
                  assertThat(notice.id()).isNotEqualTo(originalNotice);
                  assertThat(notice.params().get("outputs")).isEqualTo("2 SCU Test Ore");
                }));
  }

  private List<NotificationDto> inbox() {
    return notificationService.listOwn(recipient, PageRequest.of(0, 10)).getContent();
  }
}
