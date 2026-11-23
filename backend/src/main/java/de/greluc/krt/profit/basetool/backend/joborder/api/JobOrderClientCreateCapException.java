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
 * Thrown when a member's client create would pass one of the caps on client-created aUEC orders
 * (REQ-OXCH-009); nothing was created. The exchange answers it with the cap's own code.
 */
@Getter
public final class JobOrderClientCreateCapException extends RuntimeException {

  /** The cap the create would pass. */
  @NotNull private final Cap cap;

  /**
   * Creates the refusal.
   *
   * @param cap the cap the create would pass
   */
  public JobOrderClientCreateCapException(@NotNull Cap cap) {
    super("The member's client create would pass the " + cap + " cap.");
    this.cap = cap;
  }

  /** The two caps on the aUEC orders a member creates through clients. */
  public enum Cap {

    /** The open or in-progress orders the member created through any client. */
    OPEN_ORDERS,

    /** The orders the member created through clients within the UTC day, whatever their status. */
    DAILY_CREATES
  }
}
