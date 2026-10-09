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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankAccountResponsibleAssignedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestNoticesReconciledEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link OrgUnitBankResponsibilityService} (REQ-BANK-034): responsible-holder
 * resolution per account type and the leadership-change snapshot and audit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgUnitBankResponsibilityServiceTest {

  @Mock private BankAccountRepository bankAccountRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private BankAuditService bankAuditService;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private OrgUnitBankResponsibilityService service;

  private static OrgUnit squadron(UUID id, String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadron;
  }

  private static OrgUnit specialCommand(UUID id, String name, String shorthand) {
    SpecialCommand sk = new SpecialCommand();
    sk.setId(id);
    sk.setName(name);
    sk.setShorthand(shorthand);
    return sk;
  }

  private static BankAccount account(UUID id, String accountNo, OrgUnit orgUnit) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo(accountNo);
    account.setName(accountNo + " account");
    account.setType(orgUnit == null ? BankAccountType.AREA : BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setOrgUnit(orgUnit);
    return account;
  }

  private static BankAccount typedAccount(
      UUID id, String accountNo, BankAccountType type, OrgUnit orgUnit) {
    BankAccount account = account(id, accountNo, orgUnit);
    account.setType(type);
    return account;
  }

  private static BankAccount specialAccount(UUID id, String accountNo, BankAccountStatus status) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo(accountNo);
    account.setName(accountNo + " special account");
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(status);
    account.setOrgUnit(null);
    return account;
  }

  @Test
  void resolveResponsibleHolderUserIds_staffelAccount_returnsStaffelleiter() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(leiter);
  }

  @Test
  void resolveResponsibleHolderUserIds_skAccount_returnsSkLead() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID skLead = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0002", specialCommand(orgUnitId, "SK", "SK"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(orgUnitId, MembershipRole.SK_LEAD))
        .thenReturn(Set.of(skLead));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(skLead);
  }

  @Test
  void resolveResponsibleHolderUserIds_cartelAccount_returnsAllOlMembers() {
    UUID olId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID ol1 = UUID.randomUUID();
    UUID ol2 = UUID.randomUUID();
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setName("OL");
    BankAccount cartel = typedAccount(accountId, "KB-0003", BankAccountType.CARTEL, ol);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartel));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(olId, MembershipRole.OL_MEMBER))
        .thenReturn(Set.of(ol1, ol2));

    assertThat(service.resolveResponsibleHolderUserIds(accountId))
        .containsExactlyInAnyOrder(ol1, ol2);
  }

  @Test
  void resolveResponsibleHolderUserIds_cartelBank_returnsProfitBereichsleiter() {
    UUID profitBereichId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID bl = UUID.randomUUID();
    Bereich profit = new Bereich();
    profit.setId(profitBereichId);
    profit.setName("Profit");
    BankAccount cartelBank = typedAccount(accountId, "KB-0004", BankAccountType.CARTEL_BANK, null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartelBank));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of(profit));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitIdsAndRole(
            List.of(profitBereichId), MembershipRole.BEREICHSLEITER))
        .thenReturn(Set.of(bl));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(bl);
  }

  @Test
  void resolveResponsibleHolderUserIds_specialAccount_returnsEmptyWithoutLookup() {
    UUID accountId = UUID.randomUUID();
    BankAccount special = specialAccount(accountId, "KB-0009", BankAccountStatus.ACTIVE);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(special));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).isEmpty();
    verifyNoInteractions(orgUnitMembershipRepository);
  }

  @Test
  void resolveResponsibleHolderUserIds_missingAccount_returnsEmpty() {
    UUID accountId = UUID.randomUUID();
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).isEmpty();
  }

  @Test
  void snapshotResponsibleHolders_capturesOwnedAccountsCurrentHolders() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findByOrgUnitId(orgUnitId)).thenReturn(Optional.of(account));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of());
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    Map<UUID, Set<UUID>> snapshot = service.snapshotResponsibleHolders(orgUnitId);

    assertThat(snapshot).containsOnlyKeys(accountId);
    assertThat(snapshot.get(accountId)).containsExactly(leiter);
  }

  @Test
  void snapshotResponsibleHoldersForUser_coversEveryMembershipOrgUnitsAccount() {
    UUID userId = UUID.randomUUID();
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    when(orgUnitMembershipRepository.findOrgUnitIdsByUserId(userId)).thenReturn(Set.of(staffelId));
    when(bankAccountRepository.findByOrgUnitId(staffelId)).thenReturn(Optional.of(account));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of());
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            staffelId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    Map<UUID, Set<UUID>> snapshot = service.snapshotResponsibleHoldersForUser(userId);

    assertThat(snapshot).containsOnlyKeys(accountId);
    assertThat(snapshot.get(accountId)).containsExactly(leiter);
  }

  @Test
  void recordResponsibleHolderChanges_recordsEventWhenHolderSetChanged() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID oldLeiter = UUID.randomUUID();
    UUID newLeiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(newLeiter));

    service.recordResponsibleHolderChanges(Map.of(accountId, Set.of(oldLeiter)));

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.ACCOUNT_RESPONSIBLE_CHANGED),
            eq(accountId),
            isNull(),
            eq(newLeiter),
            any());
  }

  @Test
  void recordResponsibleHolderChanges_noEventWhenHolderSetUnchanged() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    service.recordResponsibleHolderChanges(Map.of(accountId, Set.of(leiter)));

    verifyNoInteractions(bankAuditService);
  }

  /**
   * A pending request on the account.
   *
   * @param account the account
   * @param approver the approver class it waits for, or {@code null} when it needs no approval
   * @return the request
   */
  private static BankBookingRequest pendingRequest(
      BankAccount account, BankRequestApprover approver) {
    BankBookingRequest request = new BankBookingRequest();
    request.setId(UUID.randomUUID());
    request.setAccount(account);
    request.setType(BankBookingRequestType.WITHDRAWAL);
    request.setAmount(new BigDecimal("700"));
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequestedBy(UUID.randomUUID());
    request.setRequesterHandle("requester");
    request.setRequiresOwnerApproval(approver != null);
    request.setRequiredApprover(approver);
    return request;
  }

  @Test
  void recordResponsibleHolderChanges_tellsTheNewHolderAndHandsTheOpenRequestsOver() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    UUID keptLeiter = UUID.randomUUID();
    UUID oldLeiter = UUID.randomUUID();
    UUID newLeiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(keptLeiter, newLeiter));
    BankBookingRequest awaitingHolder =
        pendingRequest(account, BankRequestApprover.RESPONSIBLE_HOLDER);
    BankBookingRequest awaitingManagement =
        pendingRequest(account, BankRequestApprover.BANK_MANAGEMENT);
    BankBookingRequest approved = pendingRequest(account, BankRequestApprover.RESPONSIBLE_HOLDER);
    approved.setOwnerApprovalGranted(true);
    when(bankBookingRequestRepository.findByAccountIdAndStatusOrderByCreatedAtAsc(
            accountId, BankBookingRequestStatus.PENDING))
        .thenReturn(List.of(awaitingHolder, awaitingManagement, approved));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));

    service.recordResponsibleHolderChanges(Map.of(accountId, Set.of(keptLeiter, oldLeiter)));

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(4)).publishEvent(events.capture());
    assertThat(events.getAllValues())
        .filteredOn(BankAccountResponsibleAssignedEvent.class::isInstance)
        .containsExactly(
            new BankAccountResponsibleAssignedEvent(accountId, newLeiter, "KB-0001", 1, actor));
    assertThat(events.getAllValues())
        .filteredOn(BankBookingRequestNoticesReconciledEvent.class::isInstance)
        .map(BankBookingRequestNoticesReconciledEvent.class::cast)
        .hasSize(3)
        .allSatisfy(
            event -> {
              assertThat(event.reconcileRecipients())
                  .containsExactlyInAnyOrder(newLeiter, oldLeiter);
              assertThat(event.accountId()).isEqualTo(accountId);
              assertThat(event.accountNo()).isEqualTo("KB-0001");
            })
        .extracting(BankBookingRequestNoticesReconciledEvent::requestId)
        .containsExactly(awaitingHolder.getId(), awaitingManagement.getId(), approved.getId());
  }

  @Test
  void recordResponsibleHolderChanges_publishesNothingWhenHolderSetUnchanged() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of(leiter));

    service.recordResponsibleHolderChanges(Map.of(accountId, Set.of(leiter)));

    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void recordResponsibleHolderChanges_aHolderOnlyLeavingTellsNobodyButReconcilesTheRequests() {
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID oldLeiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            orgUnitId, MembershipRole.STAFFELLEITER))
        .thenReturn(Set.of());
    BankBookingRequest open = pendingRequest(account, null);
    when(bankBookingRequestRepository.findByAccountIdAndStatusOrderByCreatedAtAsc(
            accountId, BankBookingRequestStatus.PENDING))
        .thenReturn(List.of(open));

    service.recordResponsibleHolderChanges(Map.of(accountId, Set.of(oldLeiter)));

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(events.capture());
    assertThat(events.getValue())
        .isInstanceOfSatisfying(
            BankBookingRequestNoticesReconciledEvent.class,
            event -> assertThat(event.reconcileRecipients()).containsExactly(oldLeiter));
  }
}
