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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The evidence that two ship types stand for the same game ship, recorded with their merge. */
@RequiredArgsConstructor
enum ShipTypeMergeReason {

  /** The Wiki UUID of the merged row is the P4K GUID the surviving row holds (REQ-P4K-053). */
  WIKI_UUID_IS_P4K_GUID(false),

  /**
   * The merged row's record is a same-name member of the variant group the surviving row's record
   * belongs to (REQ-P4K-053).
   */
  SAME_VARIANT_GROUP_AND_NAME(false),

  /** The merged row's Wiki in-game name is the surviving row's P4K-led name (REQ-P4K-053). */
  WIKI_GAME_NAME_IS_P4K_NAME(false),

  /**
   * The UEX full name of a row only UEX wrote is the surviving row's P4K-led name (REQ-P4K-053,
   * REQ-DATA-043).
   */
  UEX_FULL_NAME_IS_P4K_NAME(false),

  /**
   * The merged row's name agrees with the surviving row's English or German P4K name once each
   * drops its manufacturer word (REQ-P4K-053).
   */
  NAME_WITHOUT_MANUFACTURER_IS_P4K_NAME(false),

  /**
   * The merged row's GUID left a complete catalogue, and the surviving row holds the record that
   * succeeds it (REQ-P4K-053).
   */
  DEPARTED_FOR_HELD_SUCCESSOR(true),

  /**
   * The merged row's record is superseded by the {@code _GS} record of the same in-game name the
   * surviving row holds (REQ-P4K-053).
   */
  SUPERSEDED_BY_GS_RECORD(true),

  /**
   * The merged row's GUID left a complete catalogue, its class name extends the surviving row's
   * base by a suffix, and its name begins with the base's in-game name: a vanished edition of that
   * ship (REQ-P4K-053).
   */
  EDITION_OF_BASE(true);

  /** Whether the merged row may hold a P4K GUID, which then leaves the catalogue with it. */
  @Getter private final boolean mergingP4kRow;
}
