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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Whether one kind lists every Record of the Build, {@code completeness.<kind>} of the contract.
 *
 * @param status {@code complete} or {@code partial}
 * @param filter the p4kx Filter text of a partial kind, else {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kKindCompleteness(@Nullable String status, @Nullable String filter) {

  /** The status of a kind that lists every Record. */
  private static final String COMPLETE = "complete";

  /**
   * Whether the kind is complete; any status other than {@code complete} reads as partial.
   *
   * @return {@code true} for {@code complete}
   */
  public boolean isComplete() {
    return COMPLETE.equals(status);
  }
}
