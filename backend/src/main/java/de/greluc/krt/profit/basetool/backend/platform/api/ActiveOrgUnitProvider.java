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

package de.greluc.krt.profit.basetool.backend.platform.api;

import org.jetbrains.annotations.NotNull;

/** Supplies the effective org-unit context for the authenticated request's MDC. */
public interface ActiveOrgUnitProvider {

  /**
   * Returns the effective context's bounded log value.
   *
   * @return an org-unit UUID, {@code mine}, {@code all}, or {@code none}
   */
  @NotNull
  String activeOrgUnitLogValue();
}
