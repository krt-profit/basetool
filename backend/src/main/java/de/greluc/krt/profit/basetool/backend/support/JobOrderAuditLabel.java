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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The audit subject label for a job order: {@code #<displayId>} only, with no contact handle, so
 * the label carries no personal data (REQ-AUDIT-001).
 */
public final class JobOrderAuditLabel {

  /** Not instantiable. */
  private JobOrderAuditLabel() {}

  /**
   * Returns the label for one order.
   *
   * @param displayId the order's running number, or {@code null} if not yet assigned
   * @return {@code #<displayId>}, or {@code #?} when the number is missing
   */
  public static @NotNull String of(@Nullable Integer displayId) {
    return "#" + (displayId == null ? "?" : displayId);
  }
}
