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
 * Discriminator for the two kinds of {@link MaterialExchangeRequest} (REQ-MARKET-015): a {@link
 * #MATERIAL} request names a catalogue {@link Material}, an {@link #ITEM} request names a craftable
 * item by {@code product_key}. A DB {@code CHECK} enforces the exactly-one-branch rule.
 */
public enum MaterialExchangeRequestKind {

  /** A request for a catalogue material, in a stated SCU/piece quantity. */
  MATERIAL,

  /** A request for a craftable item (blueprint product), in a stated whole-piece quantity. */
  ITEM
}
