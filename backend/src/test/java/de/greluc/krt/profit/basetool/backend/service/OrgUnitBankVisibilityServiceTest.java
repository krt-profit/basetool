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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OrgUnitBankVisibilityService} — the view-grant write mechanics of the
 * org-unit bank seam (L3 split, #922). These lock the idempotent, audit-on-state-change contract of
 * the four grant tiers (role bucket, all-members, area-members, individual user) required by
 * REQ-AUDIT-001 / REQ-BANK-035: a grant/enable inserts a {@code bank_account_grant} row and records
 * {@code BALANCE_VISIBILITY_GRANTED} <em>only</em> when the grant did not already exist, and a
 * revoke/disable records {@code BALANCE_VISIBILITY_REVOKED} <em>only</em> when a row was actually
 * removed — so a duplicate grant never emits a phantom second audit line nor widens the balance
 * ACL, and revoking a non-existent grant records nothing. {@link
 * OrgUnitBankVisibilityService#grantUser} additionally guards on user existence.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitBankVisibilityServiceTest {

  private static final String ROLE_CODE = "ENSIGN";

  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private UserRepository userRepository;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private OrgUnitBankVisibilityService service;

  /**
   * Builds a minimal already-loaded, already-authorized account carrying only the id the write
   * mechanics use to key their grant rows and audit lines.
   *
   * @param id the account id
   * @return a bank account with the given id set
   */
  private static BankAccount account(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    return account;
  }

  @Test
  void grantRole_new_savesGrantAndAudits() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE))
        .thenReturn(false);

    service.grantRole(account, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE);

    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("MEMBERSHIP_ROLE:" + ROLE_CODE));
  }

  @Test
  void grantRole_alreadyGranted_isNoOp_noAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE))
        .thenReturn(true);

    service.grantRole(account, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE);

    verify(viewGrantRepository, never()).save(any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void revokeRole_rowDeleted_audits() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE))
        .thenReturn(1L);

    service.revokeRole(account, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE);

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_REVOKED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("MEMBERSHIP_ROLE:" + ROLE_CODE));
  }

  @Test
  void revokeRole_noRowDeleted_recordsNoAudit() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            accountId, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE))
        .thenReturn(0L);

    service.revokeRole(account, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, ROLE_CODE);

    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void setAllMembers_enableWhenAbsent_savesAndAudits() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.ALL_MEMBERS))
        .thenReturn(false);

    service.setAllMembers(account, true);

    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("ALL_MEMBERS"));
  }

  @Test
  void setAllMembers_enableWhenAlreadyGranted_noOp() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.ALL_MEMBERS))
        .thenReturn(true);

    service.setAllMembers(account, true);

    verify(viewGrantRepository, never()).save(any());
    verify(viewGrantRepository, never()).deleteByAccountIdAndGranteeKind(any(), any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void setAllMembers_disableWhenPresent_deletesAndAudits() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.ALL_MEMBERS))
        .thenReturn(true);

    service.setAllMembers(account, false);

    verify(viewGrantRepository)
        .deleteByAccountIdAndGranteeKind(accountId, BankAccountViewGranteeKind.ALL_MEMBERS);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_REVOKED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("ALL_MEMBERS"));
  }

  @Test
  void setAllMembers_disableWhenAbsent_noOp() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.ALL_MEMBERS))
        .thenReturn(false);

    service.setAllMembers(account, false);

    verify(viewGrantRepository, never()).deleteByAccountIdAndGranteeKind(any(), any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void setAreaMembers_enableWhenAbsent_savesAndAudits() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.AREA_MEMBERS))
        .thenReturn(false);

    service.setAreaMembers(account, true);

    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED),
            eq(accountId),
            isNull(),
            isNull(),
            eq("AREA_MEMBERS"));
  }

  @Test
  void setAreaMembers_enableWhenAlreadyGranted_noOp() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.existsByAccountIdAndGranteeKind(
            accountId, BankAccountViewGranteeKind.AREA_MEMBERS))
        .thenReturn(true);

    service.setAreaMembers(account, true);

    verify(viewGrantRepository, never()).save(any());
    verify(viewGrantRepository, never()).deleteByAccountIdAndGranteeKind(any(), any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void grantUser_new_savesAndAudits() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(userRepository.existsById(userId)).thenReturn(true);
    when(viewGrantRepository.existsByAccountIdAndGranteeUserId(accountId, userId))
        .thenReturn(false);

    service.grantUser(account, userId);

    verify(viewGrantRepository).save(any(BankAccountViewGrant.class));
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_GRANTED),
            eq(accountId),
            isNull(),
            eq(userId),
            eq("USER"));
  }

  @Test
  void grantUser_alreadyGranted_isNoOp_noAudit() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(userRepository.existsById(userId)).thenReturn(true);
    when(viewGrantRepository.existsByAccountIdAndGranteeUserId(accountId, userId)).thenReturn(true);

    service.grantUser(account, userId);

    verify(viewGrantRepository, never()).save(any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void grantUser_missingUser_throwsNotFound() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(userRepository.existsById(userId)).thenReturn(false);

    assertThrows(NotFoundException.class, () -> service.grantUser(account, userId));

    verify(viewGrantRepository, never()).existsByAccountIdAndGranteeUserId(any(), any());
    verify(viewGrantRepository, never()).save(any());
    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void revokeUser_noRowDeleted_recordsNoAudit() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.deleteByAccountIdAndGranteeUserId(accountId, userId)).thenReturn(0L);

    service.revokeUser(account, userId);

    verify(bankAuditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void revokeUser_rowDeleted_audits() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(viewGrantRepository.deleteByAccountIdAndGranteeUserId(accountId, userId)).thenReturn(1L);

    service.revokeUser(account, userId);

    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BALANCE_VISIBILITY_REVOKED),
            eq(accountId),
            isNull(),
            eq(userId),
            eq("USER"));
  }
}
