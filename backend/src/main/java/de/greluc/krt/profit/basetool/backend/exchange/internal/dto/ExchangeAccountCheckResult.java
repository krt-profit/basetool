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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** The answer of the exchange account check (REQ-XCH-031). */
@RequiredArgsConstructor
public enum ExchangeAccountCheckResult {
  /** The handle is the one on the member's profile, ignoring case. */
  MATCH("match"),

  /** The member stored a different handle. */
  MISMATCH("mismatch"),

  /** The member stored no handle. */
  UNKNOWN("unknown");

  /** The wire value, also the {@code outcome} label of the account-check counter. */
  @JsonValue @Getter @NotNull private final String value;
}
