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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Whether an actor may raise a booking request, answered by {@link BankAccessPolicy#admit}: {@link
 * Admitted} with its approval routing, {@link Rejection#NOT_FOUND} or {@link
 * Rejection#NOT_REQUEST_CAPABLE}.
 */
public sealed interface BankAdmission permits BankAdmission.Admitted, BankAdmission.Rejection {

  /**
   * The request may be raised; the routing is the snapshot it is stamped with (REQ-BANK-041,
   * REQ-BANK-047).
   *
   * @param requiresApproval whether a confirm needs the approver's attestation
   * @param applicableLimit the requester's display ceiling, or {@code null}
   * @param requiredApprover the approver class, or {@code null} when no approval is needed
   */
  record Admitted(
      boolean requiresApproval,
      @Nullable BigDecimal applicableLimit,
      @Nullable BankRequestApprover requiredApprover)
      implements BankAdmission {

    /** The routing of a request that needs no approval and shows no ceiling. */
    public static final Admitted UNROUTED = new Admitted(false, null, null);

    /**
     * The routing of a request that needs no approval.
     *
     * @param applicableLimit the requester's display ceiling, or {@code null}
     * @return the routing without an approver
     */
    @NotNull
    public static Admitted withoutApproval(@Nullable BigDecimal applicableLimit) {
      return new Admitted(false, applicableLimit, null);
    }

    /**
     * The routing of a request that needs the given approver.
     *
     * @param applicableLimit the requester's display ceiling, or {@code null}
     * @param approver the approver class
     * @return the routing naming the approver
     */
    @NotNull
    public static Admitted approvedBy(
        @Nullable BigDecimal applicableLimit, @NotNull BankRequestApprover approver) {
      return new Admitted(true, applicableLimit, approver);
    }
  }

  /** Why a request may not be raised. */
  enum Rejection implements BankAdmission {
    /** The account is unknown, or hidden from the actor, which answers alike (REQ-BANK-066). */
    NOT_FOUND,
    /** The account accepts no withdrawals or transfers: closed, or of a type without requests. */
    NOT_REQUEST_CAPABLE
  }
}
