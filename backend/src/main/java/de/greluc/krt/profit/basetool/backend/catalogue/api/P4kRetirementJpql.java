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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/**
 * The JPQL form of {@link P4kKeyedRow#isP4kRetired()} negated, for queries that keep only the rows
 * the P4K still lists (REQ-P4K-013). Each fragment is a compile-time constant, so a {@code @Query}
 * concatenates it; each names the alias its query must give the entity.
 */
public final class P4kRetirementJpql {

  /** The game item aliased {@code gi} is neither swept nor hidden. */
  public static final String LISTED_ITEM = " gi.p4kDeletedAt IS NULL AND gi.p4kHiddenAt IS NULL ";

  /** The blueprint aliased {@code b} is neither swept nor hidden. */
  public static final String LISTED_BLUEPRINT =
      " b.p4kDeletedAt IS NULL AND b.p4kHiddenAt IS NULL ";

  /** Non-instantiable constant holder. */
  private P4kRetirementJpql() {}
}
