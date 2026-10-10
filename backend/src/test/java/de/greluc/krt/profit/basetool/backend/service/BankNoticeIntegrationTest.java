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

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank notices end to end against Postgres (REQ-BANK-057…060): the seeded rules reach the
 * requester, the bank management and the named members, never the actor, and a revoked approval, a
 * reactivated holder and a replaced grant clear what they supersede. Runs in a rolled-back
 * transaction.
 */
@SpringBootTest
@Transactional
class BankNoticeIntegrationTest {

  private static final UUID REQUESTER = UUID.fromString("44444444-4444-4444-4444-4444444491c1");
  private static final UUID MANAGER = UUID.fromString("44444444-4444-4444-4444-4444444491c2");
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-4444444491c3");
  private static final UUID GRANTEE = UUID.fromString("44444444-4444-4444-4444-4444444491c4");
  private static final UUID OTHER_GRANTEE = UUID.fromString("44444444-4444-4444-4444-4444444491c5");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  private UUID accountId;
  private UUID entityId;

  @BeforeEach
  void seed() {
    accountId = UUID.randomUUID();
    entityId = UUID.randomUUID();
    Role management = roleRepository.findByNameIgnoreCase("Bank Management").orElseThrow();
    user(REQUESTER, "bank-requester", Set.of());
    user(MANAGER, "bank-manager", Set.of(management));
    user(ACTOR, "bank-actor", Set.of(management));
    user(GRANTEE, "bank-grantee", Set.of());
    user(OTHER_GRANTEE, "bank-other-grantee", Set.of());
  }

  private void user(UUID id, String name, Set<Role> roles) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(roles));
    userRepository.saveAndFlush(user);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  private void approve() {
    creationService.createFromEvent(
        BankNotices.approved(
            entityId,
            accountId,
            REQUESTER,
            BankBookingRequestType.WITHDRAWAL,
            new BigDecimal("2500000"),
            "KB-0003",
            "Ada",
            ACTOR));
  }

  @Test
  void anApprovedRequestTellsTheRequesterAndTheBankManagementButNotTheApprover() {
    approve();

    assertThat(inbox(REQUESTER)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_APPROVED);
    assertThat(inbox(MANAGER)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_APPROVED);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aRevokedApprovalClearsTheReadyToConfirmNotices() {
    approve();

    creationService.createFromEvent(BankNotices.approvalRevoked(entityId));

    assertThat(inbox(REQUESTER)).isEmpty();
    assertThat(inbox(MANAGER)).isEmpty();
  }

  @Test
  void aNewGrantNoticeReplacesTheGranteesEarlierOneAndLeavesOthersAlone() {
    creationService.createFromEvent(
        BankNotices.grantChanged(accountId, "KB-0003", GRANTEE, true, true, false, false, ACTOR));
    creationService.createFromEvent(
        BankNotices.grantChanged(
            accountId, "KB-0003", OTHER_GRANTEE, true, true, true, true, ACTOR));

    creationService.createFromEvent(
        BankNotices.grantChanged(accountId, "KB-0003", GRANTEE, false, true, true, false, ACTOR));

    assertThat(inbox(GRANTEE)).containsExactly(NotificationType.BANK_GRANT_CHANGED);
    assertThat(inbox(OTHER_GRANTEE)).containsExactly(NotificationType.BANK_GRANT_CHANGED);
  }

  @Test
  void aRevocationReplacesTheGrantNoticeWithTheRevocation() {
    creationService.createFromEvent(
        BankNotices.grantChanged(accountId, "KB-0003", GRANTEE, true, true, false, false, ACTOR));

    creationService.createFromEvent(
        BankNotices.grantRevoked(accountId, "KB-0003", GRANTEE, new ActorRef(ACTOR, "Ada")));

    assertThat(inbox(GRANTEE)).containsExactly(NotificationType.BANK_GRANT_REVOKED);
  }

  @Test
  void aPayoutAndAHolderTransferReachOnlyTheNamedMember() {
    ActorRef actor = new ActorRef(ACTOR, "Ada");
    creationService.createFromEvent(
        BankNotices.payoutBooked(
            entityId, GRANTEE, new BigDecimal("1000"), new BigDecimal("40"), "KB-0003", actor));
    creationService.createFromEvent(
        BankNotices.holderTransferBooked(
            UUID.randomUUID(), OTHER_GRANTEE, new BigDecimal("250"), actor));

    assertThat(inbox(GRANTEE)).containsExactly(NotificationType.BANK_PAYOUT_RECEIVED);
    assertThat(inbox(OTHER_GRANTEE))
        .containsExactly(NotificationType.BANK_HOLDER_TRANSFER_RECEIVED);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void aDeactivatedHolderWithMoneyReachesTheBankManagementUntilItIsCleared() {
    creationService.createFromEvent(
        BankNotices.holderDeactivatedWithBalance(entityId, "ex-banker", new BigDecimal("1500")));
    assertThat(inbox(MANAGER))
        .containsExactly(NotificationType.BANK_HOLDER_DEACTIVATED_WITH_BALANCE);

    creationService.createFromEvent(BankNotices.holderNoticeCleared(entityId));

    assertThat(inbox(MANAGER)).isEmpty();
  }
}
