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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OrgUnitBankApprovalLimitService} — the approval-limit write mechanics split
 * out of {@code OrgUnitBankAccessService} (L3, #922, REQ-BANK-041/-048). These close the coverage
 * gap left by {@code OrgUnitBankAccessServiceTest}, which wires the real collaborator but never
 * drives its {@code set*}/{@code clear*} paths.
 *
 * <p>The tests pin three regression-prone behaviours: (1) every mutation records its {@code
 * APPROVAL_LIMIT_SET} / {@code APPROVAL_LIMIT_CLEARED} audit event (REQ-AUDIT-001) with the exact
 * details payload; (2) the idempotent {@code upsertLimit} updates the existing tier row in place
 * (no duplicate insert against the V193 partial unique index) and selects the row through the
 * kind-correct finder branch; (3) each {@code set*} first takes the account row lock via {@link
 * BankAccountRepository#findByIdForUpdate} before the find-or-insert, and each {@code clear*}
 * records the cleared event only when a row was actually deleted ({@code removed > 0}).
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitBankApprovalLimitServiceTest {

  @Mock private BankAccountRepository bankAccountRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private OrgUnitBankApprovalLimitService service;

  /**
   * Builds a minimal managed-looking {@link BankAccount} carrying only the id the service reads
   * (for the row lock, the finders and the audit event's {@code accountId}).
   *
   * @param id the account id
   * @return a bank account with just its id populated
   */
  private static BankAccount account(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    return account;
  }

  @Test
  void setRole_insertsNewRow_recordsSetAudit_underRowLock() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);

    service.setRole(account, "ENSIGN", new BigDecimal("1000"));

    // The find-or-insert saw no existing row (Mockito default empty Optional), so a fresh row is
    // inserted carrying exactly the role-tier payload.
    ArgumentCaptor<BankAccountApprovalLimit> saved =
        ArgumentCaptor.forClass(BankAccountApprovalLimit.class);
    verify(approvalLimitRepository).save(saved.capture());
    BankAccountApprovalLimit row = saved.getValue();
    assertThat(row.getGranteeKind()).isEqualTo(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    assertThat(row.getRoleCode()).isEqualTo("ENSIGN");
    assertThat(row.getGranteeUserId()).isNull();
    assertThat(row.getAccount()).isSameAs(account);
    assertThat(row.getLimitAmount()).isEqualByComparingTo("1000");

    // The account row lock must be taken BEFORE the find-or-insert so two racing set-limit calls
    // cannot both insert into the V193 partial unique index.
    InOrder inOrder = inOrder(bankAccountRepository, approvalLimitRepository);
    inOrder.verify(bankAccountRepository).findByIdForUpdate(accountId);
    inOrder
        .verify(approvalLimitRepository)
        .findByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, "ENSIGN");

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_SET),
            eq(accountId),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("MEMBERSHIP_ROLE:ENSIGN=1000");
  }

  @Test
  void setRole_existingRow_updatesInPlace_noNewInsert() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    BankAccountApprovalLimit existing = new BankAccountApprovalLimit();
    existing.setGranteeKind(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    existing.setRoleCode("ENSIGN");
    existing.setLimitAmount(new BigDecimal("500"));
    when(approvalLimitRepository.findByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, "ENSIGN"))
        .thenReturn(Optional.of(existing));

    service.setRole(account, "ENSIGN", new BigDecimal("2000"));

    // The SAME managed row is re-saved with its amount raised — never a second insert.
    verify(approvalLimitRepository).save(same(existing));
    assertThat(existing.getLimitAmount()).isEqualByComparingTo("2000");
    assertThat(existing.getGranteeKind()).isEqualTo(BankAccountViewGranteeKind.MEMBERSHIP_ROLE);
    assertThat(existing.getRoleCode()).isEqualTo("ENSIGN");
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_SET),
            eq(accountId),
            isNull(),
            isNull(),
            eq("MEMBERSHIP_ROLE:ENSIGN=2000"));
  }

  @Test
  void clearRole_rowDeleted_recordsClearedAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(approvalLimitRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, "ENSIGN"))
        .thenReturn(1L);

    service.clearRole(account, "ENSIGN");

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_CLEARED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("MEMBERSHIP_ROLE:ENSIGN"));
  }

  @Test
  void clearRole_noRowDeleted_recordsNoAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(approvalLimitRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, "ENSIGN"))
        .thenReturn(0L);

    service.clearRole(account, "ENSIGN");

    // No row was removed, so no phantom APPROVAL_LIMIT_CLEARED may be written (REQ-AUDIT-001).
    verifyNoInteractions(bankAuditService);
  }

  @Test
  void setUser_missingUser_throwsNotFound_noUpsertNoAudit() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(userRepository.existsById(userId)).thenReturn(false);

    assertThrows(
        NotFoundException.class, () -> service.setUser(account, userId, new BigDecimal("250")));

    // A missing user must abort before the row lock, the upsert and the audit event.
    verifyNoInteractions(bankAccountRepository, approvalLimitRepository, bankAuditService);
  }

  @Test
  void setUser_existingUser_upsertsByUserId_recordsSetAudit() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(userRepository.existsById(userId)).thenReturn(true);

    service.setUser(account, userId, new BigDecimal("250"));

    // The USER tier must be resolved by grantee user id (not by role code), then inserted fresh.
    verify(approvalLimitRepository).findByAccountIdAndGranteeUserId(accountId, userId);
    verify(bankAccountRepository).findByIdForUpdate(accountId);
    ArgumentCaptor<BankAccountApprovalLimit> saved =
        ArgumentCaptor.forClass(BankAccountApprovalLimit.class);
    verify(approvalLimitRepository).save(saved.capture());
    BankAccountApprovalLimit row = saved.getValue();
    assertThat(row.getGranteeKind()).isEqualTo(BankAccountViewGranteeKind.USER);
    assertThat(row.getGranteeUserId()).isEqualTo(userId);
    assertThat(row.getRoleCode()).isNull();
    assertThat(row.getLimitAmount()).isEqualByComparingTo("250");

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_SET),
            eq(accountId),
            isNull(),
            eq(userId),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("USER=250");
  }

  @Test
  void clearUser_noRowDeleted_recordsNoAudit() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(approvalLimitRepository.deleteByAccountIdAndGranteeUserId(accountId, userId))
        .thenReturn(0L);

    service.clearUser(account, userId);

    verifyNoInteractions(bankAuditService);
  }

  @Test
  void setAllMembers_upsertsByKind_recordsSetAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);

    service.setAllMembers(account, new BigDecimal("1000"));

    // The payload-less ALL_MEMBERS tier must be resolved by kind alone.
    verify(approvalLimitRepository)
        .findByAccountIdAndGranteeKind(accountId, BankAccountViewGranteeKind.ALL_MEMBERS);
    verify(bankAccountRepository).findByIdForUpdate(accountId);
    ArgumentCaptor<BankAccountApprovalLimit> saved =
        ArgumentCaptor.forClass(BankAccountApprovalLimit.class);
    verify(approvalLimitRepository).save(saved.capture());
    BankAccountApprovalLimit row = saved.getValue();
    assertThat(row.getGranteeKind()).isEqualTo(BankAccountViewGranteeKind.ALL_MEMBERS);
    assertThat(row.getRoleCode()).isNull();
    assertThat(row.getGranteeUserId()).isNull();
    assertThat(row.getLimitAmount()).isEqualByComparingTo("1000");

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_SET),
            eq(accountId),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("ALL_MEMBERS=1000");
  }

  @Test
  void clearAllMembers_rowDeleted_recordsClearedAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(approvalLimitRepository.deleteByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.ALL_MEMBERS))
        .thenReturn(1L);

    service.clearAllMembers(account);

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_CLEARED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("ALL_MEMBERS"));
  }

  @Test
  void setAreaMembers_upsertsByKind_recordsSetAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);

    service.setAreaMembers(account, new BigDecimal("750"));

    verify(approvalLimitRepository)
        .findByAccountIdAndGranteeKind(accountId, BankAccountViewGranteeKind.AREA_MEMBERS);
    ArgumentCaptor<BankAccountApprovalLimit> saved =
        ArgumentCaptor.forClass(BankAccountApprovalLimit.class);
    verify(approvalLimitRepository).save(saved.capture());
    BankAccountApprovalLimit row = saved.getValue();
    assertThat(row.getGranteeKind()).isEqualTo(BankAccountViewGranteeKind.AREA_MEMBERS);
    assertThat(row.getRoleCode()).isNull();
    assertThat(row.getGranteeUserId()).isNull();
    assertThat(row.getLimitAmount()).isEqualByComparingTo("750");

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.APPROVAL_LIMIT_SET),
            eq(accountId),
            isNull(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("AREA_MEMBERS=750");
  }

  @Test
  void clearAreaMembers_noRowDeleted_recordsNoAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(approvalLimitRepository.deleteByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.AREA_MEMBERS))
        .thenReturn(0L);

    service.clearAreaMembers(account);

    verifyNoInteractions(bankAuditService);
  }
}
