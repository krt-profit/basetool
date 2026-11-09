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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What an actor may do with one account, answered by {@link BankAccessPolicy#accessTo}: {@link
 * Visible} with at least one right, or {@link Hidden}, which an unknown account and an account the
 * actor may do nothing with answer alike (REQ-BANK-066).
 */
public sealed interface BankAccountAccess
    permits BankAccountAccess.Visible, BankAccountAccess.Hidden {

  /**
   * Whether the actor holds the right.
   *
   * @param right the right
   * @return {@code true} iff the access is {@link Visible} and carries it
   */
  boolean has(@NotNull BankAccountRight right);

  /**
   * An account the actor may do something with.
   *
   * @param accountId the account
   * @param rights the actor's rights on it; never empty
   * @param approvalLimit the actor's display ceiling for a request (REQ-BANK-041): {@code T1} on
   *     the KRT account, else the best matching limit; {@code null} when the actor may not request,
   *     is exempt, or no tier matches
   */
  record Visible(
      @NotNull UUID accountId,
      @NotNull @Unmodifiable Set<BankAccountRight> rights,
      @Nullable BigDecimal approvalLimit)
      implements BankAccountAccess {

    /**
     * Copies the rights.
     *
     * @param accountId the account
     * @param rights the rights; never empty
     * @param approvalLimit the display ceiling, or {@code null}
     * @throws IllegalArgumentException when {@code rights} is empty
     */
    public Visible {
      if (rights.isEmpty()) {
        throw new IllegalArgumentException("A visible account carries at least one right");
      }
      rights = Set.copyOf(rights);
    }

    @Override
    public boolean has(@NotNull BankAccountRight right) {
      return rights.contains(right);
    }

    /**
     * Whether the actor raises requests here without any approver's sign-off: the responsible
     * holder of a request-capable account (REQ-BANK-041).
     *
     * @return {@code true} iff the actor holds {@link BankAccountRight#REQUEST} and {@link
     *     BankAccountRight#RESPONSIBLE_HOLDER}
     */
    public boolean approvalExempt() {
      return has(BankAccountRight.REQUEST) && has(BankAccountRight.RESPONSIBLE_HOLDER);
    }

    /**
     * Whether the actor may open the org-unit settings panel to set the target or configure the
     * visibility.
     *
     * @return {@code true} iff the actor holds {@link BankAccountRight#SET_TARGET} or {@link
     *     BankAccountRight#CONFIGURE_VISIBILITY}
     */
    public boolean canManageSettings() {
      return has(BankAccountRight.SET_TARGET) || has(BankAccountRight.CONFIGURE_VISIBILITY);
    }

    /**
     * Whether the org-unit side discloses the account to the actor: viewable or configurable
     * (REQ-BANK-066).
     *
     * @return {@code true} iff the actor views, manages the settings of or configures the approval
     *     limits of the account
     */
    public boolean disclosedOnOrgUnitSide() {
      return has(BankAccountRight.VIEW)
          || canManageSettings()
          || has(BankAccountRight.CONFIGURE_APPROVAL_LIMITS);
    }
  }

  /** An unknown account, or one the actor may do nothing with. */
  enum Hidden implements BankAccountAccess {
    /** The one hidden answer. */
    INSTANCE;

    @Override
    public boolean has(@NotNull BankAccountRight right) {
      return false;
    }
  }
}
