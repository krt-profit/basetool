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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Why the backend refused a job-order request of the exchange; the {@code reason} label of its
 * metric (REQ-OXCH-009, REQ-OBS-025).
 */
@RequiredArgsConstructor
public enum JobOrderExchangeRefusal {

  /** The order is unknown or outside the member's read set. */
  NOT_FOUND("not_found"),

  /** The member may not make this change to the order. */
  NOT_PERMITTED("not_permitted"),

  /** The requesting and responsible unit are not a pair the member may create for. */
  UNITS_NOT_OFFERED("units_not_offered"),

  /** The order changed since the client read the version it echoed. */
  VERSION_CONFLICT("version_conflict"),

  /** The order's responsibility no longer fits the hierarchy. */
  RESPONSIBILITY_INVALID("responsibility_invalid"),

  /** The order is closed. */
  CLOSED("closed"),

  /** The order's first money operation was accepted, so it cannot be cancelled. */
  MONEY_STARTED("money_started"),

  /** The new target lies at or below the net handover. */
  TARGET_TOO_LOW("target_too_low"),

  /** The member already holds the configured number of open client-created orders. */
  OPEN_ORDER_CAP("open_order_cap"),

  /** The member already created the configured number of orders through clients today. */
  DAILY_CREATE_CAP("daily_create_cap"),

  /** The job-order family switch is off. */
  SWITCHED_OFF("switched_off"),

  /** The job-order writes switch is off. */
  WRITES_SWITCHED_OFF("writes_switched_off");

  /** The metric label value. */
  @Getter @NotNull private final String tag;
}
