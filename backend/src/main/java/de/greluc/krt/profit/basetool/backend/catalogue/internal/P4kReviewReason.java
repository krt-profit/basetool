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

/** Why the P4K import put a record on the review list (REQ-P4K-016). */
public enum P4kReviewReason {

  /**
   * An unmatched item or ship whose {@code playability} is {@code special}, {@code null} or
   * unknown, met while creating new rows (REQ-P4K-009).
   */
  UNCLEAR_PLAYABILITY,

  /**
   * A row an earlier seeding created that the current seeding rule of its kind would not create
   * (REQ-P4K-010).
   */
  SEEDED_NOT_CREATABLE,

  /**
   * A GUID that several rows would claim, or a row that several GUIDs would claim (REQ-P4K-007).
   */
  GUID_CONFLICT,

  /**
   * A blueprint ingredient's {@code resourceGuid} or a {@code refinedSubtypeGuid} that no commodity
   * record carries as its {@code subtypeGuid} (REQ-P4K-024); the case's GUID is that {@code
   * ResourceType}.
   */
  RESOURCE_JOIN_NONE,

  /**
   * A blueprint ingredient's {@code resourceGuid} or a {@code refinedSubtypeGuid} that several
   * commodity records share as their {@code subtypeGuid}, so none is created or linked
   * (REQ-P4K-024, REQ-P4K-026); the case's GUID is that {@code ResourceType}.
   */
  RESOURCE_JOIN_SEVERAL
}
