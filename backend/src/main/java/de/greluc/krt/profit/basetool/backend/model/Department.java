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
 * The fixed set of Kartell departments ("Bereiche") and their frozen brand colour identity (epic
 * #692, REQ-ORG-026). A {@link Bereich} org unit optionally carries one of these so the org chart
 * (and any other Bereich-coloured surface) can tint its nodes with the department's Bereichsfarbe.
 *
 * <p>Each value maps one-to-one to a design-system department-colour token (the {@code
 * --color-dept-*} CSS custom properties defined in the frontend's {@code styles.css}). The mapping
 * is intentionally kept on the <em>frontend</em> (enum name → CSS class) so this backend enum stays
 * free of presentation concerns; the value names below are the contract that binds the two sides:
 *
 * <ul>
 *   <li>{@link #PROFIT} → {@code --color-dept-profit} (green {@code #239E33})
 *   <li>{@link #SUB_RADAR} → {@code --color-dept-sub-radar} (red {@code #A3000A})
 *   <li>{@link #RAUMUEBERLEGENHEIT} → {@code --color-dept-raumueberlegenheit} (teal {@code
 *       #37BBC0})
 *   <li>{@link #FORSCHUNG} → {@code --color-dept-forschung} (blue {@code #355DDC})
 *   <li>{@link #MARINEKORPS} → {@code --color-dept-marinekorps} (purple {@code #7A5E96})
 *   <li>{@link #SEARCH_RESCUE} → {@code --color-dept-search-rescue} (yellow {@code #FFD23F})
 * </ul>
 *
 * <p>The set is closed and frozen per the design manual; adding a department means adding both a
 * value here and its colour token in the design system. Persisted as a string ({@code
 * EnumType.STRING}) on the nullable {@code org_unit.department} column (V166) so the stored value
 * reads the same across Flyway, JPA and the wire DTOs.
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
