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

package de.greluc.krt.profit.basetool.backend.repository;

import java.util.UUID;

/**
 * Projection row of a two-parent matrix table — a price or yield row keyed by (catalogue row,
 * terminal) — carrying only the two parent ids and the row's own id. The UEX matrix syncs read the
 * whole key set with one query and resolve "does this pair exist yet" in memory rather than with a
 * lookup per row (BE-PERF-09).
 */
public interface PairKeyRef {

  /**
   * The first parent: the material, game item or other catalogue row the matrix row belongs to.
   *
   * @return the catalogue-side parent id
   */
  UUID getParentId();

  /**
   * The second parent: the terminal the matrix row is quoted at.
   *
   * @return the terminal id
   */
  UUID getTerminalId();

  /**
   * The matrix row's own primary key.
   *
   * @return the row id
   */
  UUID getId();
}
