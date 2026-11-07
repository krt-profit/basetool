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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemPlayability;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The seeding rule of items and ships (REQ-P4K-009): with "create new items and ships" ticked, an
 * unmatched record is created only when its {@code playability} is {@code player}; {@code ai} and
 * {@code mission} are only counted, {@code special}, {@code null} and an unknown value go to
 * review.
 */
enum P4kSeeding {

  /** The record is created. */
  CREATE,

  /** The record is never created and only counted as unmatched. */
  COUNT_ONLY,

  /** The record's playability is unclear; it goes to review instead of being created. */
  REVIEW;

  /**
   * The rule for an item.
   *
   * @param playability the item's playability, {@code null} when absent or unknown
   * @return the decision
   */
  @NotNull
  static P4kSeeding forItem(@Nullable P4kItemPlayability playability) {
    return switch (playability) {
      case null -> REVIEW;
      case PLAYER -> CREATE;
      case MISSION -> COUNT_ONLY;
      case SPECIAL -> REVIEW;
    };
  }

  /**
   * The rule for a ship.
   *
   * @param playability the ship's playability, {@code null} when absent or unknown
   * @return the decision
   */
  @NotNull
  static P4kSeeding forShip(@Nullable P4kShipPlayability playability) {
    return switch (playability) {
      case null -> REVIEW;
      case PLAYER -> CREATE;
      case AI, MISSION -> COUNT_ONLY;
      case SPECIAL -> REVIEW;
    };
  }

  /**
   * Handles an unmatched record that this rule may not create: it reports the record unmatched and,
   * while creating is on, opens the review case of an unclear playability.
   *
   * @param kind the kind
   * @param record the record
   * @param guid the record's GUID, or {@code null}
   * @param context the run
   */
  void notCreated(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @Nullable UUID guid,
      @NotNull P4kImportContext context) {
    if (this == REVIEW && guid != null && context.isCreateItemsAndShips()) {
      context.getReport().review(kind, record, guid, P4kReviewReason.UNCLEAR_PLAYABILITY, null);
    }
    context.getReport().unmatched(kind, record);
  }
}
