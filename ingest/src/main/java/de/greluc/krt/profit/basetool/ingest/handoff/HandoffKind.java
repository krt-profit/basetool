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

package de.greluc.krt.profit.basetool.ingest.handoff;

/**
 * Which kind of draft a handoff carries — the frontend uses it to pick the correct pre-fill surface
 * and the matching {@code ?handoff=} landing page (REQ-INGEST-004). Stored alongside the staged
 * draft in Redis.
 */
public enum HandoffKind {
  /** A refinery-order draft; opens the refinery create form. */
  REFINERY,

  /** A personal-blueprint import preview; opens the blueprint import review surface. */
  BLUEPRINT,

  /**
   * A client's change set the mass-change guard held back; opens its confirmation on the connected
   * applications page (REQ-XCH-021).
   */
  MASS_CHANGE
}
