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

import static de.greluc.krt.profit.basetool.backend.util.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountApprovalLimitRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval-limit write mechanics of {@link OrgUnitBankAccessService} (L3 split, #922): the
 * idempotent upsert / clear of the three per-tier approval-limit rows (membership-role bucket,
 * all-members, individual user, REQ-BANK-041) and the row-locked {@link #upsertLimit} that
 * serialises concurrent set-limit calls. Each mutation records its {@code APPROVAL_LIMIT_SET} /
 * {@code APPROVAL_LIMIT_CLEARED} audit event (REQ-AUDIT-001).
 *
 * <p>This collaborator holds <em>only</em> the persistence + audit mechanics; the account
 * resolution, the org-unit authorization ({@code requireCanConfigureApprovalLimits}) and the
 * limit-bucket / all-members type validation stay in {@link OrgUnitBankAccessService}, the single
 * sanctioned {@code OwnerScopeService}↔bank bridge. It injects {@link BankAccountRepository}
 * <em>only for the {@code SELECT … FOR UPDATE} row lock</em> in {@link #upsertLimit} and
 * deliberately does NOT inject {@link OwnerScopeService}, so it never becomes a second
 * org-unit-aware bridge (ADR-0020, {@code orgUnitAwareBankSeamIsContainedToOneClass}). The
 * read-side {@code resolveApplicableLimit} (which consults the caller's org-unit roles) stays in
 * the facade for exactly that reason.
 *
 * <p>Each mutation is {@code @Transactional} (propagation {@code REQUIRED}) so it joins the
 * read-write transaction the facade already opened; the caller re-reads the settings snapshot in
 * the same transaction afterwards.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankApprovalLimitService {

  private final BankAccountRepository bankAccountRepository;
  private final BankAccountApprovalLimitRepository approvalLimitRepository;
  private final UserRepository userRepository;
  private final BankAuditService bankAuditService;

  /**
   * Sets or changes a role-bucket approval limit on an account and records the set audit event
   * (REQ-BANK-041). The role code was validated against the account's limit buckets by the caller.
   *
   * @param account the already-loaded, already-authorized account
   * @param roleCode the role bucket ({@code MembershipRole} name)
   * @param limit the whole-aUEC ceiling (&gt;= 0)
   */
  @Transactional
  public void setRole(
      @NotNull BankAccount account, @NotNull String roleCode, @NotNull BigDecimal limit) {
    upsertLimit(account, BankAccountViewGranteeKind.MEMBERSHIP_ROLE, roleCode, null, limit);
    bankAuditService.record(
        BankAuditEventType.APPROVAL_LIMIT_SET,
        account.getId(),
        null,
        null,
        "MEMBERSHIP_ROLE:" + roleCode + "=" + plain(limit));
  }

  /**
   * Removes a role-bucket approval limit from an account, recording the cleared audit event when a
   * row was actually deleted (REQ-BANK-041).
   *
   * @param account the already-loaded, already-authorized account
   * @param roleCode the role bucket to clear
   */
  @Transactional
  public void clearRole(@NotNull BankAccount account, @NotNull String roleCode) {
    long removed =
        approvalLimitRepository.deleteByAccountIdAndGranteeKindAndRoleCode(
            account.getId(), BankAccountViewGranteeKind.MEMBERSHIP_ROLE, roleCode);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.APPROVAL_LIMIT_CLEARED,
          account.getId(),
          null,
          null,
          "MEMBERSHIP_ROLE:" + roleCode);
    }
  }

  /**
   * Sets or changes the all-members approval limit on an account and records the set audit event
   * (REQ-BANK-041). The all-members-tier support was validated by the caller.
   *
   * @param account the already-loaded, already-authorized account
   * @param limit the whole-aUEC ceiling (&gt;= 0)
   */
  @Transactional
  public void setAllMembers(@NotNull BankAccount account, @NotNull BigDecimal limit) {
    upsertLimit(account, BankAccountViewGranteeKind.ALL_MEMBERS, null, null, limit);
    bankAuditService.record(
        BankAuditEventType.APPROVAL_LIMIT_SET,
        account.getId(),
        null,
        null,
        AuditDetails.of("ALL_MEMBERS", plain(limit)));
  }

  /**
   * Removes the all-members approval limit from an account, recording the cleared audit event when
   * a row was actually deleted (REQ-BANK-041).
   *
   * @param account the already-loaded, already-authorized account
   */
  @Transactional
  public void clearAllMembers(@NotNull BankAccount account) {
    long removed =
        approvalLimitRepository.deleteByAccountIdAndGranteeKind(
            account.getId(), BankAccountViewGranteeKind.ALL_MEMBERS);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.APPROVAL_LIMIT_CLEARED, account.getId(), null, null, "ALL_MEMBERS");
    }
  }

  /**
   * Sets or changes the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto and
   * records the set audit event (REQ-BANK-048): the ceiling for any member of the whole area
   * cascade (Bereichsleitung + child Staffel/SK members) who matches no more specific tier. The
   * area-members-tier support was validated by the caller.
   *
   * @param account the already-loaded, already-authorized account
   * @param limit the whole-aUEC ceiling (&gt;= 0)
   */
  @Transactional
  public void setAreaMembers(@NotNull BankAccount account, @NotNull BigDecimal limit) {
    upsertLimit(account, BankAccountViewGranteeKind.AREA_MEMBERS, null, null, limit);
    bankAuditService.record(
        BankAuditEventType.APPROVAL_LIMIT_SET,
        account.getId(),
        null,
        null,
        AuditDetails.of("AREA_MEMBERS", plain(limit)));
  }

  /**
   * Removes the "Mitglieder des Bereichs" cascade approval limit from a Bereichskonto, recording
   * the cleared audit event when a row was actually deleted (REQ-BANK-048).
   *
   * @param account the already-loaded, already-authorized account
   */
  @Transactional
  public void clearAreaMembers(@NotNull BankAccount account) {
    long removed =
        approvalLimitRepository.deleteByAccountIdAndGranteeKind(
            account.getId(), BankAccountViewGranteeKind.AREA_MEMBERS);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.APPROVAL_LIMIT_CLEARED, account.getId(), null, null, "AREA_MEMBERS");
    }
  }

  /**
   * Sets or changes an individual user's approval limit on an account and records the set audit
   * event (REQ-BANK-041); the most specific tier, overriding any role/all-members limit for that
   * user.
   *
   * @param account the already-loaded, already-authorized account
   * @param userId the user the limit addresses
   * @param limit the whole-aUEC ceiling (&gt;= 0)
   * @throws NotFoundException when the user does not exist
   */
  @Transactional
  public void setUser(
      @NotNull BankAccount account, @NotNull UUID userId, @NotNull BigDecimal limit) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    upsertLimit(account, BankAccountViewGranteeKind.USER, null, userId, limit);
    bankAuditService.record(
        BankAuditEventType.APPROVAL_LIMIT_SET,
        account.getId(),
        null,
        userId,
        AuditDetails.of("USER", plain(limit)));
  }

  /**
   * Removes an individual user's approval limit from an account, recording the cleared audit event
   * when a row was actually deleted (REQ-BANK-041).
   *
   * @param account the already-loaded, already-authorized account
   * @param userId the user whose limit to clear
   */
  @Transactional
  public void clearUser(@NotNull BankAccount account, @NotNull UUID userId) {
    long removed =
        approvalLimitRepository.deleteByAccountIdAndGranteeUserId(account.getId(), userId);
    if (removed > 0) {
      bankAuditService.record(
          BankAuditEventType.APPROVAL_LIMIT_CLEARED, account.getId(), null, userId, "USER");
    }
  }

  /**
   * Idempotent upsert of one approval-limit row: updates the existing tier row's amount in place
   * (dirty-checking) or inserts a new one. The partial unique indexes guarantee at most one row per
   * (account, tier).
   *
   * @param account the account
   * @param kind the tier kind
   * @param roleCode the role code for a role tier, else {@code null}
   * @param userId the user for a user tier, else {@code null}
   * @param amount the new ceiling
   */
  private void upsertLimit(
      @NotNull BankAccount account,
      @NotNull BankAccountViewGranteeKind kind,
      @Nullable String roleCode,
      @Nullable UUID userId,
      @NotNull BigDecimal amount) {
    // Serialise concurrent set-limit calls for the same account on its row lock (a plain SELECT FOR
    // UPDATE that does NOT bump the account @Version) so the find-or-insert below cannot race two
    // inserts into a V193 partial unique index (uq_bank_appr_limit_*). The lock is released at tx
    // end.
    bankAccountRepository.findByIdForUpdate(account.getId());
    Optional<BankAccountApprovalLimit> existing =
        switch (kind) {
          case MEMBERSHIP_ROLE, GLOBAL_ROLE ->
              approvalLimitRepository.findByAccountIdAndGranteeKindAndRoleCode(
                  account.getId(), kind, roleCode);
          case USER ->
              approvalLimitRepository.findByAccountIdAndGranteeUserId(account.getId(), userId);
          case ALL_MEMBERS, AREA_MEMBERS ->
              approvalLimitRepository.findByAccountIdAndGranteeKind(account.getId(), kind);
        };
    BankAccountApprovalLimit limit =
        existing.orElseGet(
            () -> {
              BankAccountApprovalLimit fresh = new BankAccountApprovalLimit();
              fresh.setAccount(account);
              fresh.setGranteeKind(kind);
              fresh.setRoleCode(roleCode);
              fresh.setGranteeUserId(userId);
              return fresh;
            });
    limit.setLimitAmount(amount);
    approvalLimitRepository.save(limit);
  }
}
