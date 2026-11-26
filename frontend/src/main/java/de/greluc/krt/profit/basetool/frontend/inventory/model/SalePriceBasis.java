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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

/**
 * Frontend mirror of the terminal price "Mein Lager" values a member's materials at, chosen in the
 * profile (REQ-INV-072).
 */
public enum SalePriceBasis {

  /** The mean sale price across the terminals that buy the material; the default. */
  AVERAGE,

  /** The highest sale price, at the best-paying terminal. */
  HIGHEST,

  /** The lowest sale price, at the worst-paying terminal. */
  LOWEST
}
