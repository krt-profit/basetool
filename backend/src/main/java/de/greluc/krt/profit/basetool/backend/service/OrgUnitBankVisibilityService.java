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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountViewGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * View-grant write mechanics of {@link OrgUnitBankAccessService} (L3 split, #922): the idempotent
 * grant / revoke of the four {@code bank_account_grant} tiers (membership-role bucket, all-members,
 * individual user) plus the {@link #createViewGrant} factory that dedupes the former three
 * copy-pasted grant-insert blocks. Each mutation records its {@code BALANCE_VISIBILITY_GRANTED} /
 * {@code BALANCE_VISIBILITY_REVOKED} audit event (REQ-AUDIT-001).
 *
 * <p>This collaborator holds <em>only</em> the persistence + audit mechanics; the account
 * resolution, the org-unit authorization ({@code requireCanConfigureVisibility}) and the
 * role-bucket validation stay in {@link OrgUnitBankAccessService}, which is the single sanctioned
 * {@code OwnerScopeService}↔bank bridge (ADR-0020, {@code
 * orgUnitAwareBankSeamIsContainedToOneClass}). By design this service depends on neither {@link
 * OwnerScopeService} nor {@link
 * de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository}, so it never becomes a
 * second bridge; the caller passes the already-loaded, already-authorized {@link BankAccount}.
 *
 * <p>Each mutation is {@code @Transactional} (propagation {@code REQUIRED}) so it joins the
 * read-write transaction the facade already opened — the caller re-reads the account's settings
 * snapshot in the same transaction after the grant lands.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankVisibilityService {

  private final BankAccountViewGrantRepository viewGrantRepository;
  private final UserRepository userRepository;
  private final BankAuditService bankAuditService;

  /**
   * Adds a role-bucket view grant to an account if it is not already granted, then records the
   * grant audit event (REQ-BANK-035). Idempotent: an already-granted bucket is a no-op with no
   * audit line. The {@code kind} is the pre-resolved grant kind ({@code MEMBERSHIP_ROLE} for an
   * org-unit account, {@code GLOBAL_ROLE} for a Sonderkonto) validated by the caller.
   *
   * @param account the already-loaded, already-authorized account
   * @param kind the grant kind the role bucket maps to
   * @param roleCode the role bucket to grant
   */
  @Transactional
  public void grantRole(
      @NotNull BankAccount account,
      @NotNull BankAccountViewGranteeKind kind,
      @NotNull String roleCode) {
    if (!viewGrantRepository.existsByAccountIdAndGranteeKindAndRoleCode(
        account.getId(), kind, roleCode)) {
      createViewGrant(account, kind, roleCode, null);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_GRANTED,
          account.getId(),
          null,
          null,
          kind.name() + ":" + roleCode);
    }
  }

  /**
   * Removes a role-bucket view grant from an account and records the revoke audit event when a row
   * was actually deleted (REQ-BANK-035).
   *
   * @param account the already-loaded, already-authorized account
   * @param kind the grant kind the role bucket maps to
   * @param roleCode the role bucket to revoke
   */
  @Transactional
  public void revokeRole(
      @NotNull BankAccount account,
      @NotNull BankAccountViewGranteeKind kind,
      @NotNull String roleCode) {
    long removed =
        viewGrantRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            account.getId(), kind, roleCode);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_REVOKED,
          account.getId(),
          null,
          null,
          kind.name() + ":" + roleCode);
    }
  }

  /**
   * Enables or disables the all-members view grant of an account (REQ-BANK-035), recording the
   * matching grant/revoke audit event only when the grant state actually changes. Idempotent.
   *
   * @param account the already-loaded, already-authorized account
   * @param enabled whether all members may view the account
   */
  @Transactional
  public void setAllMembers(@NotNull BankAccount account, boolean enabled) {
    boolean exists =
        viewGrantRepository.existsByAccountIdAndGranteeKind(
            account.getId(), BankAccountViewGranteeKind.ALL_MEMBERS);
    if (enabled && !exists) {
      createViewGrant(account, BankAccountViewGranteeKind.ALL_MEMBERS, null, null);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_GRANTED,
          account.getId(),
          null,
          null,
          "ALL_MEMBERS");
    } else if (!enabled && exists) {
      viewGrantRepository.deleteByAccountIdAndGranteeKind(
          account.getId(), BankAccountViewGranteeKind.ALL_MEMBERS);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_REVOKED,
          account.getId(),
          null,
          null,
          "ALL_MEMBERS");
    }
  }

  /**
   * Enables or disables the "Mitglieder des Bereichs" cascade view grant of a Bereichskonto
   * (REQ-BANK-048): every member of the whole area cascade — the Bereichsleitung plus every member
   * of the Bereich's child Staffeln/SKs — may view it. Records the matching grant/revoke audit
   * event only when the grant state actually changes. Idempotent; area-members support was
   * validated by the caller.
   *
   * @param account the already-loaded, already-authorized account
   * @param enabled whether the whole area cascade may view the account
   */
  @Transactional
  public void setAreaMembers(@NotNull BankAccount account, boolean enabled) {
    boolean exists =
        viewGrantRepository.existsByAccountIdAndGranteeKind(
            account.getId(), BankAccountViewGranteeKind.AREA_MEMBERS);
    if (enabled && !exists) {
      createViewGrant(account, BankAccountViewGranteeKind.AREA_MEMBERS, null, null);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_GRANTED,
          account.getId(),
          null,
          null,
          "AREA_MEMBERS");
    } else if (!enabled && exists) {
      viewGrantRepository.deleteByAccountIdAndGranteeKind(
          account.getId(), BankAccountViewGranteeKind.AREA_MEMBERS);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_REVOKED,
          account.getId(),
          null,
          null,
          "AREA_MEMBERS");
    }
  }

  /**
   * Grants an individual user view access to an account if not already granted, then records the
   * grant audit event (REQ-BANK-035). Idempotent.
   *
   * @param account the already-loaded, already-authorized account
   * @param userId the user to grant
   * @throws NotFoundException when the user does not exist
   */
  @Transactional
  public void grantUser(@NotNull BankAccount account, @NotNull UUID userId) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    if (!viewGrantRepository.existsByAccountIdAndGranteeUserId(account.getId(), userId)) {
      createViewGrant(account, BankAccountViewGranteeKind.USER, null, userId);
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_GRANTED, account.getId(), null, userId, "USER");
    }
  }

  /**
   * Revokes an individual user's view access to an account and records the revoke audit event when
   * a row was actually deleted (REQ-BANK-035).
   *
   * @param account the already-loaded, already-authorized account
   * @param userId the user to revoke
   */
  @Transactional
  public void revokeUser(@NotNull BankAccount account, @NotNull UUID userId) {
    long removed = viewGrantRepository.deleteByAccountIdAndGranteeUserId(account.getId(), userId);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.BALANCE_VISIBILITY_REVOKED, account.getId(), null, userId, "USER");
    }
  }

  /**
   * Inserts one {@code bank_account_grant} row for the given account, tier kind and
   * (kind-dependent) role code / user id — the dedup of the three previously copy-pasted
   * grant-insert blocks (addRoleVisibility / setAllMembersVisibility / addUserVisibility). Setting
   * {@code roleCode} / {@code granteeUserId} to {@code null} for the tiers that do not use them is
   * a no-op on the fresh entity (both default to {@code null}), so the persisted row is identical
   * to the former inline inserts.
   *
   * @param account the owning account
   * @param kind the grantee tier kind
   * @param roleCode the role bucket for a role tier, else {@code null}
   * @param userId the user for a user tier, else {@code null}
   */
  private void createViewGrant(
      @NotNull BankAccount account,
      @NotNull BankAccountViewGranteeKind kind,
      @Nullable String roleCode,
      @Nullable UUID userId) {
    BankAccountViewGrant grant = new BankAccountViewGrant();
    grant.setAccount(account);
    grant.setGranteeKind(kind);
    grant.setRoleCode(roleCode);
    grant.setGranteeUserId(userId);
    viewGrantRepository.save(grant);
  }
}
