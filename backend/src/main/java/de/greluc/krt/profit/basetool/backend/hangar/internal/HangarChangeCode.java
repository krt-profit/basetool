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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

/**
 * What an admin did to one of a member's ships, the {@code changeCode} of the member's notice
 * (REQ-HANGAR-008); the notice carries the constant's name.
 */
enum HangarChangeCode {
  /** The admin added the ship. */
  ADDED,
  /** The admin changed the ship's values, its origin included. */
  UPDATED,
  /** The admin deleted the ship. */
  DELETED,
  /** The admin moved the ship to another org unit or out of one. */
  ORG_UNIT_CHANGED
}
