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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The fixed set of Kartell departments whose brand colour a {@link Bereich} may carry
 * (REQ-ORG-026).
 *
 * <p>Each value maps one-to-one, by name, to a frontend {@code --color-dept-*} token (e.g. {@link
 * #PROFIT} to {@code --color-dept-profit}); adding a department needs both a value here and its
 * token. Persisted as a string on the nullable {@code org_unit.department} column.
 */
public enum Department {
  /** Profit-Bereich — green. */
  PROFIT,
  /** Sub-Radar / covert — red. */
  SUB_RADAR,
  /** Raumüberlegenheit / space superiority — teal. */
  RAUMUEBERLEGENHEIT,
  /** Forschung / research — blue. */
  FORSCHUNG,
  /** Marinekorps / marine corps — purple. */
  MARINEKORPS,
  /** Search &amp; Rescue — yellow. */
  SEARCH_RESCUE
}
