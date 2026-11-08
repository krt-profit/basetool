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

package de.greluc.krt.profit.basetool.frontend.refinery.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/** Frontend mirror of the backend {@code RefineryOrderPart}: a part an order update writes. */
@DtoMirror
public enum RefineryOrderPart {
  /** The game-data section: location, method, timer, status and goods. */
  GAME_DATA,
  /** The finances section: expenses, other expenses and ore sales. */
  FINANCES,
  /** The mission link, which belongs to neither section. */
  MISSION_LINK
}
