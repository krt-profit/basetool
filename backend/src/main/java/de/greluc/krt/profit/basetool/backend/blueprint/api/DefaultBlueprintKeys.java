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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import org.jetbrains.annotations.Nullable;

/** The default blueprint set, for other modules (plan §5.3). */
public interface DefaultBlueprintKeys {

  /**
   * Tells whether the given normalized product key belongs to the default set, which a member
   * cannot remove.
   *
   * @param productKey the normalized product key, or {@code null}
   * @return {@code true} iff the key is a default product key
   */
  boolean isDefault(@Nullable String productKey);
}
