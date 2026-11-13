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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

/** Action offered for an inspected candidate in the P4K review workspace. */
public enum P4kReviewAction {
  /** Shows candidate evidence without changing a row. */
  INSPECT,
  /** Records a confirmed P4K manufacturer identity on an existing company. */
  ATTACH_MANUFACTURER_ALIAS,
  /** Keeps the existing manufacturer label for a conflicting P4K record. */
  KEEP_MANUFACTURER_NAME,
  /** Consolidates two verified manufacturer rows. */
  MERGE_MANUFACTURER,
  /** Attaches an unclaimed P4K identity to the matching catalogue row. */
  ATTACH_P4K,
  /** Corrects the P4K identity held by a catalogue row. */
  CORRECT_P4K_GUID,
  /** Withdraws a queued ship identity repair and reopens its decision. */
  CANCEL_SHIP_REPAIR,
  /** Consolidates a verified UEX item with a SC Wiki item. */
  MERGE_ITEM,
  /** Links a UEX place to its matching P4K starmap object. */
  LINK_UEX_PLACE,
  /** Links a P4K shop to a UEX terminal at its place. */
  LINK_UEX_TERMINAL
}
