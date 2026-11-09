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
 * Why the backend refused a bank request of the exchange; the {@code reason} label of its metric.
 */
@RequiredArgsConstructor
public enum BankExchangeRefusal {

  /** The account or request is unknown, hidden from the member or closed. */
  NOT_FOUND("not_found"),

  /** The member called the staff queue without a bank role, grant or pending approval. */
  NOT_BANK_STAFF("not_bank_staff"),

  /** The member already holds the configured number of pending requests. */
  OPEN_REQUEST_CAP("open_request_cap"),

  /** The bank switch is off. */
  SWITCHED_OFF("switched_off");

  /** The metric label value. */
  @Getter @NotNull private final String tag;
}
