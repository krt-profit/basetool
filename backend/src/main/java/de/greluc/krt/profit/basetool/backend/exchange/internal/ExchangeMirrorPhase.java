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

/** When a write of the registry mirror runs; the {@code phase} label of its metric. */
@RequiredArgsConstructor
public enum ExchangeMirrorPhase {

  /** Inside a restricting change, before its commit; a failure fails the change. */
  PRE_COMMIT("pre_commit"),

  /** After a change committed; a failure is left to the reconcile. */
  POST_COMMIT("post_commit"),

  /** After a change rolled back, undoing a restriction written before the commit. */
  ROLLBACK("rollback"),

  /** At application startup. */
  STARTUP("startup"),

  /** The periodic reconcile. */
  RECONCILE("reconcile"),

  /** At application startup while mirroring is off, switching off a document left behind. */
  SWITCHED_OFF("switched_off");

  /** The metric label value. */
  @Getter @NotNull private final String tag;
}
