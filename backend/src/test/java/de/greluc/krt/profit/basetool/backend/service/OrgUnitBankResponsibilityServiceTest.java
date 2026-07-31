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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BereichRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link OrgUnitBankResponsibilityService} (REQ-BANK-034/-026/-047, ADR-0070).
 * Covers the derived responsible-holder reverse-resolution per account type and the
 * leadership-change audit (snapshot + record) split out of {@link OrgUnitBankAccessService} under
 * audit Thema 7 (#14). Lenient strictness mirrors the parent suite's convention across the many
 * independent scenarios.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgUnitBankResponsibilityServiceTest {

  @Mock private BankAccountRepository bankAccountRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private BankAuditService bankAuditService;

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
    // REQ-BANK-034/-026: the reverse resolution for the notification engine — a Staffelkonto's
    // responsible holders are the STAFFELLEITER of the owning Staffel.
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
    // REQ-BANK-034: an SK-Konto's responsible holder is the SK_LEAD of the owning Spezialkommando.
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
    // REQ-BANK-034: the CARTEL/KRT account is held collegially by all OL members.
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
    // REQ-BANK-034: the Kartellbankkonto's responsible holder is the BEREICHSLEITER of every PROFIT
    // Bereich, unioned.
    UUID profitBereichId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID bl = UUID.randomUUID();
    Bereich profit = new Bereich();
    profit.setId(profitBereichId);
    profit.setName("Profit");
    BankAccount cartelBank = typedAccount(accountId, "KB-0004", BankAccountType.CARTEL_BANK, null);
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(cartelBank));
    when(bereichRepository.findByDepartment(any())).thenReturn(List.of(profit));
    when(orgUnitMembershipRepository.findUserIdsByOrgUnitAndRole(
            profitBereichId, MembershipRole.BEREICHSLEITER))
        .thenReturn(Set.of(bl));

    assertThat(service.resolveResponsibleHolderUserIds(accountId)).containsExactly(bl);
  }

  @Test
  void resolveResponsibleHolderUserIds_specialAccount_returnsEmptyWithoutLookup() {
    // REQ-BANK-034: a Sonderkonto has no responsible holder — empty, and no membership lookup runs.
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
    // ADR-0070: before a leadership change the seam snapshots the current responsible holder(s) of
    // the account the org unit owns; a non-Profit org unit does not pull in CARTEL/CARTEL_BANK.
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
    // ADR-0070: deleting a user snapshots the responsible holders of every account tied to any org
    // unit the user belongs to, so a leader-drop by the cascade is audited regardless of org unit.
    UUID userId = UUID.randomUUID();
    UUID staffelId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID leiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(staffelId, "Own", "OWN"));
    // The member org units come from the bare-id projection, never from the membership entities:
    // loading those inside the user-deletion transaction is what caused the production
    // TransientPropertyValueException (see UserDeletionForeignKeyIntegrityTest).
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
    // REQ-BANK-034/ADR-0070: a leadership change that moves the derived responsible-holder set
    // records one ACCOUNT_RESPONSIBLE_CHANGED event; the sole new holder is the target user.
    UUID orgUnitId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID oldLeiter = UUID.randomUUID();
    UUID newLeiter = UUID.randomUUID();
    BankAccount account = account(accountId, "KB-0001", squadron(orgUnitId, "Own", "OWN"));
    when(bankAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
    // The recompute after the mutation resolves the new Staffelleiter.
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
    // ADR-0070: a leadership change that leaves the derived responsible-holder set unchanged (a
    // non-leader rank shuffle) records nothing.
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
}
