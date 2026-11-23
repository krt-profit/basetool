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

/** A read route of the job orders on the exchange; the {@code route} label of its metric. */
@RequiredArgsConstructor
public enum JobOrderExchangeReadRoute {

  /** The snapshot of the member's job-order set. */
  SNAPSHOT("snapshot"),

  /** The change feed of the member's job-order set. */
  FEED("feed"),

  /** The choices and limits a client offers before a create. */
  CREATE_OPTIONS("create_options");

  /** The metric label value. */
  @Getter @NotNull private final String tag;
}
