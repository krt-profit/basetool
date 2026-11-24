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
 * What one recomputation of a job order's exchange fingerprint found; the {@code outcome} label of
 * its metric.
 */
@RequiredArgsConstructor
public enum JobOrderFingerprintOutcome {

  /** The fingerprint differed from the stored one and was written with a new instant. */
  CHANGED("changed"),

  /** The fingerprint equalled the stored one; nothing was written. */
  UNCHANGED("unchanged");

  /** The metric label value. */
  @Getter @NotNull private final String tag;
}
