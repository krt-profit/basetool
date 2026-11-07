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

/** How the values of a P4K blueprint modifier act on the stat (contract, "Tiers"). */
public enum BlueprintModifierValueKind {

  /** The values are factors ({@code multiplier}); {@code 1} leaves the stat unchanged. */
  MULTIPLIER,

  /** The values are summands ({@code additive}); {@code 0} leaves the stat unchanged. */
  ADDITIVE
}
