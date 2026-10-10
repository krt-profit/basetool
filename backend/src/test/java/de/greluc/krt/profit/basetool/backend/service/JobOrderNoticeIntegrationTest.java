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

import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job-order notices end to end against Postgres (REQ-ORDERS-041…044): the seeded rules reach
 * the leadership of the right unit, an assignment is cleared for the one member only, and the
 * closed order's earlier creation notices go. Runs in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class JobOrderNoticeIntegrationTest {

  private static final UUID LOGISTICIAN = UUID.fromString("44444444-4444-4444-4444-4444444470c1");
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-4444444470c2");
  private static final UUID ASSIGNEE = UUID.fromString("44444444-4444-4444-4444-4444444470c3");
  private static final ActorRef ACTING = new ActorRef(ACTOR, "Ada");
  private static final OrgUnitRef IRIDIUM =
      new OrgUnitRef(Squadron.IRIDIUM_ID, OrgUnitKind.SQUADRON);

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;

  private UUID orderId;

  @BeforeEach
  void seed() {
    orderId = UUID.randomUUID();
    User logistician = user(LOGISTICIAN, "orders-logistician");
    user(ACTOR, "orders-actor");
    user(ASSIGNEE, "orders-assignee");
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(LOGISTICIAN, Squadron.IRIDIUM_ID));
    membership.setUser(logistician);
    membership.setLogistician(true);
    membership.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(membership);
  }

  private User user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  @Test
  void aFinishedOrderTellsTheLogisticianOfTheRequestingUnitButNotTheActor() {
    creationService.createFromEvent(
        JobOrderNotices.finished(orderId, 12, "Ada", IRIDIUM, "IRI", "COMPLETED", ACTING));

    assertThat(inbox(LOGISTICIAN)).containsExactly(NotificationType.JOB_ORDER_FINISHED);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aDeletedOrderIsTaggedSoItsNoticeHasNoPageToLink() {
    creationService.createFromEvent(
        JobOrderNotices.finished(orderId, 12, "Ada", IRIDIUM, "IRI", "DELETED", ACTING));

    assertThat(
            notificationRepository
                .findAllByRecipientUserId(LOGISTICIAN, PageRequest.of(0, 5))
                .getContent())
        .extracting(Notification::getEntityType)
        .containsExactly(JobOrderNotices.ENTITY_TYPE_DELETED);
  }

  @Test
  void anOrderWithoutARequestingUnitTellsNobodyAboutItsEnd() {
    creationService.createFromEvent(
        JobOrderNotices.finished(orderId, 12, "Ada", null, null, "REJECTED", ACTING));

    assertThat(inbox(LOGISTICIAN)).isEmpty();
  }

  @Test
  void aReassignmentTellsTheNewUnitAndClearsTheEarlierCreationNotices() {
    notificationRepository.save(
        Notification.builder()
            .recipientUserId(LOGISTICIAN)
            .type(NotificationType.JOB_ORDER_CREATED)
            .entityType("JOB_ORDER")
            .entityId(orderId)
            .build());

    creationService.createFromEvent(
        JobOrderNotices.reassigned(orderId, 12, "Ada", "KRT", IRIDIUM, "IRI", ACTING));

    assertThat(inbox(LOGISTICIAN)).containsExactly(NotificationType.JOB_ORDER_REASSIGNED);
  }

  @Test
  void anAssignmentNotifiesTheAssigneeAndTheRemovalClearsItForThemOnly() {
    creationService.createFromEvent(
        JobOrderNotices.assigneeAdded(orderId, 12, "Ada", ASSIGNEE, ACTING));
    assertThat(inbox(ASSIGNEE)).containsExactly(NotificationType.JOB_ORDER_ASSIGNED);

    creationService.createFromEvent(JobOrderNotices.assigneeRemoved(orderId, ASSIGNEE, ACTING));

    assertThat(inbox(ASSIGNEE)).isEmpty();
  }

  @Test
  void aWithdrawnClaimTellsTheMemberWhoMadeIt() {
    creationService.createFromEvent(
        JobOrderNotices.claimWithdrawn(
            orderId, 12, "Ada", ASSIGNEE, "Quantanium", "ORDER_CHANGED", ACTING));

    assertThat(inbox(ASSIGNEE)).containsExactly(NotificationType.JOB_ORDER_CLAIM_WITHDRAWN);
    assertThat(inbox(LOGISTICIAN)).isEmpty();
  }
}
