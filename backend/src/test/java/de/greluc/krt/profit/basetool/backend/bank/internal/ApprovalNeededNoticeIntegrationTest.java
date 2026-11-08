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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestUpdatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.Notification;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.internal.NotificationCreationService;
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
 * The approval-needed notice end to end against Postgres (REQ-NOTIF-033, REQ-BANK-041): the seeded
 * rules tell the required approver that their approval is needed instead of the generic new-request
 * notice, clear it once the request is approved, decided or withdrawn, raise it again when the
 * approval is revoked, and re-decide it on a correction. Runs in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class ApprovalNeededNoticeIntegrationTest {

  private static final UUID REQUESTER = UUID.fromString("44444444-4444-4444-4444-4444444531c1");
  private static final UUID MANAGER = UUID.fromString("44444444-4444-4444-4444-4444444531c2");
  private static final UUID GRANTEE = UUID.fromString("44444444-4444-4444-4444-4444444531c3");
  private static final UUID APPROVER = UUID.fromString("44444444-4444-4444-4444-4444444531c4");
  private static final BigDecimal AMOUNT = new BigDecimal("2500000");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankAccountGrantRepository grantRepository;
  @Autowired private BankBookingRequestRepository requestRepository;

  private BankAccount account;

  @BeforeEach
  void seed() {
    Role management = roleRepository.findByNameIgnoreCase("Bank Management").orElseThrow();
    user(REQUESTER, "approval-requester", Set.of());
    user(MANAGER, "approval-manager", Set.of(management));
    User grantee = user(GRANTEE, "approval-grantee", Set.of());
    user(APPROVER, "approval-approver", Set.of(management));
    account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName("Freigabe-Probe " + UUID.randomUUID());
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account = accountRepository.saveAndFlush(account);
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(new BankAccountGrantId(GRANTEE, account.getId()));
    grant.setUser(grantee);
    grant.setAccount(account);
    grant.setCanWithdraw(true);
    grantRepository.saveAndFlush(grant);
  }

  private User user(UUID id, String name, Set<Role> roles) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(roles));
    return userRepository.saveAndFlush(user);
  }

  private BankBookingRequest pendingRequest(boolean needsApproval) {
    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(account);
    request.setType(BankBookingRequestType.WITHDRAWAL);
    request.setAmount(AMOUNT);
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequestedBy(REQUESTER);
    request.setRequesterHandle("approval-requester");
    request.setRequiresOwnerApproval(needsApproval);
    request.setRequiredApprover(needsApproval ? BankRequestApprover.BANK_MANAGEMENT : null);
    return requestRepository.saveAndFlush(request);
  }

  private void created(BankBookingRequest request) {
    creationService.createFromEvent(
        new BankBookingRequestCreatedEvent(
            request.getId(),
            account.getId(),
            BankBookingRequestType.WITHDRAWAL,
            AMOUNT,
            account.getAccountNo(),
            "approval-requester",
            null,
            REQUESTER));
  }

  private void approve(BankBookingRequest request) {
    request.setOwnerApprovalGranted(true);
    requestRepository.saveAndFlush(request);
    creationService.createFromEvent(
        BankNotices.approved(
            request.getId(),
            account.getId(),
            REQUESTER,
            BankBookingRequestType.WITHDRAWAL,
            AMOUNT,
            account.getAccountNo(),
            "approval-approver",
            APPROVER));
  }

  private void revoke(BankBookingRequest request) {
    request.setOwnerApprovalGranted(false);
    requestRepository.saveAndFlush(request);
    creationService.createFromEvent(
        BankNotices.approvalRevoked(
            request.getId(),
            account.getId(),
            BankBookingRequestType.WITHDRAWAL,
            AMOUNT,
            account.getAccountNo(),
            "approval-requester",
            APPROVER));
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  @Test
  void theRequiredApproverGetsTheApprovalNoticeInsteadOfTheGenericOne() {
    created(pendingRequest(true));

    assertThat(inbox(MANAGER))
        .containsExactly(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
    assertThat(inbox(GRANTEE)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_CREATED);
    assertThat(inbox(REQUESTER)).isEmpty();
  }

  @Test
  void aRequestWithinTheLimitRaisesOnlyTheGenericNotice() {
    created(pendingRequest(false));

    assertThat(inbox(MANAGER)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_CREATED);
  }

  @Test
  void anApprovalClearsTheNoticeAndARevocationRaisesItAgain() {
    BankBookingRequest request = pendingRequest(true);
    created(request);

    approve(request);
    assertThat(inbox(MANAGER))
        .doesNotContain(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED)
        .doesNotContain(NotificationType.BANK_BOOKING_REQUEST_CREATED);

    revoke(request);
    assertThat(inbox(MANAGER))
        .containsExactly(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
    assertThat(inbox(APPROVER))
        .containsExactly(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
    assertThat(inbox(GRANTEE)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_CREATED);
  }

  @Test
  void aCancellationClearsTheNotice() {
    BankBookingRequest request = pendingRequest(true);
    created(request);

    creationService.createFromEvent(
        new BankBookingRequestCancelledEvent(request.getId(), account.getId(), REQUESTER));

    assertThat(inbox(MANAGER)).isEmpty();
  }

  @Test
  void aConfirmationClearsTheNotice() {
    BankBookingRequest request = pendingRequest(true);
    created(request);

    creationService.createFromEvent(
        new BankBookingRequestConfirmedEvent(
            request.getId(), account.getId(), account.getAccountNo(), AMOUNT, REQUESTER, GRANTEE));

    assertThat(inbox(MANAGER))
        .doesNotContain(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
  }

  @Test
  void aRejectionClearsTheNotice() {
    BankBookingRequest request = pendingRequest(true);
    created(request);

    creationService.createFromEvent(
        new BankBookingRequestRejectedEvent(
            request.getId(),
            account.getId(),
            account.getAccountNo(),
            AMOUNT,
            "no",
            REQUESTER,
            GRANTEE));

    assertThat(inbox(MANAGER))
        .doesNotContain(NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
  }

  @Test
  void aCorrectionBelowTheLimitReplacesTheApprovalNoticeWithTheGenericOne() {
    BankBookingRequest request = pendingRequest(true);
    created(request);

    request.setRequiresOwnerApproval(false);
    request.setRequiredApprover(null);
    requestRepository.saveAndFlush(request);
    creationService.createFromEvent(
        new BankBookingRequestUpdatedEvent(
            request.getId(),
            account.getId(),
            BankBookingRequestType.WITHDRAWAL,
            AMOUNT,
            account.getAccountNo(),
            "approval-requester",
            null,
            REQUESTER));

    assertThat(inbox(MANAGER)).containsExactly(NotificationType.BANK_BOOKING_REQUEST_UPDATED);
  }
}
