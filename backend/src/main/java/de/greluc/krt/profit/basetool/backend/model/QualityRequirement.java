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
 * Quality floor a derived material requirement of an item order must meet: the refining-grade
 * threshold or no floor. Chosen per material at order creation, defaulting from the blueprint
 * ingredient's {@code minQuality}.
 */
public enum QualityRequirement {

  /** Requires refining-grade quality (650+); only inventory at or above that tier satisfies it. */
  GOOD,

  /** No quality floor ("Keine"); inventory of any quality satisfies the requirement. */
  NONE
}
