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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The blueprint variant families, for other modules counting blueprints (plan §5.3). */
public interface BlueprintFamilies {

  /**
   * Returns the key a blueprint name counts under: its variant family when variants count together,
   * else its normalized name.
   *
   * @param name the blueprint or product name, or {@code null}
   * @param countWithVariants whether variants count together
   * @return the counting key
   */
  @NotNull
  String matchKey(@Nullable String name, boolean countWithVariants);

  /**
   * Tells whether a name is a magazine.
   *
   * @param name the name, or {@code null}
   * @return {@code true} iff the name is a magazine
   */
  boolean isMagazine(@Nullable String name);
}
