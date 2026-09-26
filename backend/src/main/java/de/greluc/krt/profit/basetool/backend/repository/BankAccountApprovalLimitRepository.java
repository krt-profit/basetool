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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.BankAccountApprovalLimit;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BankAccountApprovalLimit}, the per-account, per-tier approval
 * ceilings (REQ-BANK-041). Used only through {@code OrgUnitBankAccessService}; deletes are
 * single-row derived deletes.
 */
@Repository
public interface BankAccountApprovalLimitRepository
    extends JpaRepository<BankAccountApprovalLimit, UUID> {

  /**
   * Returns every approval limit of one account — the settings panel of a single account and the
   * per-request limit resolution for a single account.
   *
   * @param accountId the account whose limits to list; never {@code null}
   * @return the account's approval limits; never {@code null}, possibly empty
   */
  List<BankAccountApprovalLimit> findByAccountId(UUID accountId);

  /**
   * Returns every approval limit of the given accounts in one query (REQ-DATA-003).
   *
   * @param accountIds the accounts whose limits to collect; never {@code null}; empty yields an
   *     empty result
   * @return the approval limits across the given accounts; never {@code null}
   */
  List<BankAccountApprovalLimit> findByAccountIdIn(Collection<UUID> accountIds);

  /**
   * Looks up an account's role-tier limit (used by the set-limit upsert to update in place).
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind {@code MEMBERSHIP_ROLE} or {@code GLOBAL_ROLE}; never {@code null}
   * @param roleCode the role code; never {@code null}
   * @return the existing limit, or empty
   */
  Optional<BankAccountApprovalLimit> findByAccountIdAndGranteeKindAndRoleCode(
      UUID accountId, BankAccountViewGranteeKind granteeKind, String roleCode);

  /**
   * Looks up an account's limit of a payload-less kind (the {@code ALL_MEMBERS} bucket).
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind the grant kind to look up; never {@code null}
   * @return the existing limit, or empty
   */
  Optional<BankAccountApprovalLimit> findByAccountIdAndGranteeKind(
      UUID accountId, BankAccountViewGranteeKind granteeKind);

  /**
   * Looks up an account's individual-user limit (used by the set-limit upsert to update in place).
   *
   * @param accountId the account; never {@code null}
   * @param granteeUserId the addressed user; never {@code null}
   * @return the existing limit, or empty
   */
  Optional<BankAccountApprovalLimit> findByAccountIdAndGranteeUserId(
      UUID accountId, UUID granteeUserId);

  /**
   * Removes the account's role limit of the given kind and role code.
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind {@code MEMBERSHIP_ROLE} or {@code GLOBAL_ROLE}; never {@code null}
   * @param roleCode the role code to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeKindAndRoleCode(
      UUID accountId, BankAccountViewGranteeKind granteeKind, String roleCode);

  /**
   * Removes the account's limit of the given kind (used for the {@code ALL_MEMBERS} bucket).
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind the grant kind to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeKind(UUID accountId, BankAccountViewGranteeKind granteeKind);

  /**
   * Removes the account's individual-user limit for the given user.
   *
   * @param accountId the account; never {@code null}
   * @param granteeUserId the addressed user to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeUserId(UUID accountId, UUID granteeUserId);
}
