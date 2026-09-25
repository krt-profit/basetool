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

import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountViewGranteeKind;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BankAccountViewGrant}, the holder-configured extra read access
 * to a bank account (REQ-BANK-035). A row's existence grants its audience view access.
 */
@Repository
public interface BankAccountViewGrantRepository extends JpaRepository<BankAccountViewGrant, UUID> {

  /**
   * Returns every view grant of one account — the settings view of a single account and the
   * per-card drill-in visibility check.
   *
   * @param accountId the account whose grants to list; never {@code null}
   * @return the account's view grants; never {@code null}, possibly empty
   */
  List<BankAccountViewGrant> findByAccountId(UUID accountId);

  /**
   * Returns every view grant of the given accounts in one query (REQ-DATA-003).
   *
   * @param accountIds the accounts whose grants to collect; never {@code null}; empty yields an
   *     empty result
   * @return the view grants across the given accounts; never {@code null}
   */
  List<BankAccountViewGrant> findByAccountIdIn(Collection<UUID> accountIds);

  /**
   * Whether the account already carries a grant of the given kind.
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind the grant kind to probe; never {@code null}
   * @return {@code true} iff such a grant exists
   */
  boolean existsByAccountIdAndGranteeKind(UUID accountId, BankAccountViewGranteeKind granteeKind);

  /**
   * Whether the account already carries a role grant of the given kind and role code.
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind {@code MEMBERSHIP_ROLE} or {@code GLOBAL_ROLE}; never {@code null}
   * @param roleCode the role code; never {@code null}
   * @return {@code true} iff such a grant exists
   */
  boolean existsByAccountIdAndGranteeKindAndRoleCode(
      UUID accountId, BankAccountViewGranteeKind granteeKind, String roleCode);

  /**
   * Whether the account already carries an individual-user grant for the given user.
   *
   * @param accountId the account; never {@code null}
   * @param granteeUserId the granted user; never {@code null}
   * @return {@code true} iff such a grant exists
   */
  boolean existsByAccountIdAndGranteeUserId(UUID accountId, UUID granteeUserId);

  /**
   * Removes the account's grant of the given kind (used for the {@code ALL_MEMBERS} toggle).
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind the grant kind to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeKind(UUID accountId, BankAccountViewGranteeKind granteeKind);

  /**
   * Removes the account's role grant of the given kind and role code.
   *
   * @param accountId the account; never {@code null}
   * @param granteeKind {@code MEMBERSHIP_ROLE} or {@code GLOBAL_ROLE}; never {@code null}
   * @param roleCode the role code to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeKindAndRoleCode(
      UUID accountId, BankAccountViewGranteeKind granteeKind, String roleCode);

  /**
   * Removes the account's individual-user grant for the given user.
   *
   * @param accountId the account; never {@code null}
   * @param granteeUserId the granted user to remove; never {@code null}
   * @return the number of rows removed (0 or 1)
   */
  long deleteByAccountIdAndGranteeUserId(UUID accountId, UUID granteeUserId);
}
