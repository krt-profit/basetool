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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a client's write on an existing job order breaks a rule the exchange answers with a
 * job-order code of its own (REQ-OXCH-005); nothing was written. A hidden or unknown order, a write
 * the member may not make and a stale section version are refused with the web's exceptions.
 */
@Getter
public final class JobOrderClientWriteRefusedException extends RuntimeException {

  /** The rule the write broke. */
  @NotNull private final Refusal refusal;

  /**
   * Creates the refusal.
   *
   * @param refusal the rule the write broke
   */
  public JobOrderClientWriteRefusedException(@NotNull Refusal refusal) {
    super("The client's job-order write was refused: " + refusal + ".");
    this.refusal = refusal;
  }

  /** The rules of a client write on an existing order that carry a job-order code. */
  public enum Refusal {

    /** The order's responsibility no longer fits the Profit hierarchy (REQ-ORDERS-055). */
    RESPONSIBILITY_INVALID,

    /** The order's status does not admit the write. */
    CLOSED,

    /**
     * A money operation of the order reached its accepted, durable submission boundary, the
     * hard-delete boundary of REQ-ORDERS-063, so a client cannot cancel it (REQ-OXCH-006).
     */
    MONEY_STARTED,

    /**
     * The new target lies at or below the order's current net handover, so it would complete the
     * order (REQ-OXCH-007).
     */
    TARGET_TOO_LOW
  }
}
