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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.event.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgRelativeRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end coverage of the notification rule engine against the real Postgres test container: it
 * validates the V156 schema and seed, that every recipient-resolution query executes, and that the
 * creation pipeline persists rows for the resolved recipients (the seeded rule plus an extra rule
 * coexisting). The async listener is bypassed — the creation service is driven directly — so the
 * assertions are deterministic.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationRuleEngineIntegrationTest {

  @Autowired private NotificationRuleRepository notificationRuleRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationCreationService notificationCreationService;
  @Autowired private RecipientResolutionService recipientResolutionService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private static JobOrderCreatedEvent eventForRandomUnits() {
    return new JobOrderCreatedEvent(
        UUID.randomUUID(),
        123,
        "Integration order",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "IRI",
        new OrgUnitRef(UUID.randomUUID(), OrgUnitKind.SQUADRON),
        "MATERIAL",
        null);
  }

  @Test
  void seededDefaultRuleIsPresentWithSelectors() {
    NotificationRule seeded =
        notificationRuleRepository
            .findByIdWithSelectors(UUID.fromString("62200000-0000-0000-0000-000000000001"))
            .orElseThrow();

    assertThat(seeded.getEventType()).isEqualTo(NotificationEventType.JOB_ORDER_CREATED);
    assertThat(seeded.getNotificationType()).isEqualTo(NotificationType.JOB_ORDER_CREATED);
    assertThat(seeded.isExcludeActor()).isTrue();
    assertThat(seeded.getSelectors()).hasSize(4);
  }

  @Test
  void recipientResolutionQueriesExecuteAgainstPostgres() {
    UUID randomOrgUnit = UUID.randomUUID();
    // Each call exercises a distinct JPQL query; a clean execution (even when empty) is the point.
    assertThat(recipientResolutionService.resolveByRole("OFFICER")).isNotNull();
    assertThat(recipientResolutionService.resolveByRole("ADMIN")).isNotNull();
    assertThat(
            recipientResolutionService.resolveOrgRelative(OrgRelativeRole.OFFICER, randomOrgUnit))
        .isNotNull();
    assertThat(recipientResolutionService.resolveOrgRelative(OrgRelativeRole.LEAD, randomOrgUnit))
        .isNotNull();
    assertThat(
            recipientResolutionService.resolveOrgRelative(
                OrgRelativeRole.LOGISTICIAN, randomOrgUnit))
        .isNotNull();
    assertThat(
            recipientResolutionService.resolveOrgRelative(
                OrgRelativeRole.MISSION_MANAGER, randomOrgUnit))
        .isNotNull();
  }

  @Test
  void createFromEventPersistsRowForSpecificUserRule() {
    UUID recipient = UUID.randomUUID();
    NotificationRule extraRule =
        transactionTemplate.execute(
            status -> {
              NotificationRule rule =
                  NotificationRule.builder()
                      .eventType(NotificationEventType.JOB_ORDER_CREATED)
                      .notificationType(NotificationType.JOB_ORDER_CREATED)
                      .description("integration-test specific-user rule")
                      .enabled(true)
                      .excludeActor(false)
                      .build();
              rule.addSelector(
                  NotificationRuleSelector.builder()
                      .kind(SelectorKind.SPECIFIC_USER)
                      .userSub(recipient)
                      .build());
              return notificationRuleRepository.saveAndFlush(rule);
            });

    try {
      JobOrderCreatedEvent event = eventForRandomUnits();
      Set<UUID> recipients = notificationCreationService.createFromEvent(event);

      assertThat(recipients).isNotEmpty();
      assertThat(notificationRepository.findAllByRecipientSub(recipient, Pageable.unpaged()))
          .singleElement()
          .satisfies(
              n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.JOB_ORDER_CREATED);
                assertThat(n.getEntityId()).isEqualTo(event.entityId());
                assertThat(n.isRead()).isFalse();
              });
    } finally {
      transactionTemplate.executeWithoutResult(
          status -> notificationRuleRepository.deleteById(extraRule.getId()));
    }
  }

  @Test
  void discordRegistrationPendingRuleNotifiesEveryAdmin() {
    // REQ-NOTIF-012 end-to-end (the previously-unverified "exactly one notification per admin"
    // acceptance): the V174 seed rule resolves a DISCORD_REGISTRATION_PENDING event to every ADMIN
    // via its ROLE selector and persists exactly one unread notification per admin. Seeds one admin
    // holding the DataInitializer-seeded ADMIN role, fires the event, and cleans up afterwards.
    UUID adminSub = UUID.randomUUID();
    UUID newUserId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          Role admin = roleRepository.findByNameIgnoreCase("Admin").orElseThrow();
          User u = new User();
          u.setId(adminSub);
          u.setUsername("it-admin-" + adminSub);
          u.setApprovalStatus(ApprovalStatus.ACTIVE);
          u.setRoles(new HashSet<>(Set.of(admin)));
          userRepository.saveAndFlush(u);
        });

    try {
      NotificationRule seeded =
          notificationRuleRepository
              .findByIdWithSelectors(UUID.fromString("62200000-0000-0000-0000-000000000005"))
              .orElseThrow();
      assertThat(seeded.getEventType())
          .isEqualTo(NotificationEventType.DISCORD_REGISTRATION_PENDING);
      assertThat(seeded.getNotificationType())
          .isEqualTo(NotificationType.DISCORD_REGISTRATION_PENDING);
      assertThat(seeded.isExcludeActor()).isFalse();
      assertThat(seeded.getSelectors())
          .singleElement()
          .satisfies(
              s -> {
                assertThat(s.getKind()).isEqualTo(SelectorKind.ROLE);
                assertThat(s.getRoleCode()).isEqualTo("ADMIN");
              });

      Set<UUID> recipients =
          notificationCreationService.createFromEvent(
              new DiscordRegistrationPendingEvent(newUserId, "newbie"));

      assertThat(recipients).isNotEmpty();
      assertThat(notificationRepository.findAllByRecipientSub(adminSub, Pageable.unpaged()))
          .singleElement()
          .satisfies(
              n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.DISCORD_REGISTRATION_PENDING);
                assertThat(n.getEntityType())
                    .isEqualTo(DiscordRegistrationPendingEvent.ENTITY_TYPE);
                assertThat(n.getEntityId()).isEqualTo(newUserId);
                assertThat(n.isRead()).isFalse();
              });
    } finally {
      transactionTemplate.executeWithoutResult(
          status -> {
            notificationRepository
                .findAllByRecipientSub(adminSub, Pageable.unpaged())
                .forEach(n -> notificationRepository.deleteById(n.getId()));
            userRepository.deleteById(adminSub);
          });
    }
  }

  @Test
  void ruleCrudRoundTripsThroughRepository() {
    UUID id =
        transactionTemplate.execute(
            status -> {
              NotificationRule rule =
                  NotificationRule.builder()
                      .eventType(NotificationEventType.JOB_ORDER_CREATED)
                      .notificationType(NotificationType.JOB_ORDER_CREATED)
                      .enabled(true)
                      .excludeActor(true)
                      .build();
              rule.addSelector(
                  NotificationRuleSelector.builder()
                      .kind(SelectorKind.ROLE)
                      .roleCode("ADMIN")
                      .build());
              return notificationRuleRepository.saveAndFlush(rule).getId();
            });

    assertThat(notificationRuleRepository.findByIdWithSelectors(id))
        .get()
        .satisfies(r -> assertThat(r.getSelectors()).hasSize(1));

    transactionTemplate.executeWithoutResult(status -> notificationRuleRepository.deleteById(id));

    assertThat(notificationRuleRepository.findById(id)).isEmpty();
  }
}
