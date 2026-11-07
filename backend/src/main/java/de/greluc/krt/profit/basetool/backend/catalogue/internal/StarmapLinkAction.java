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

/** What an admin does with a UEX location's starmap link (REQ-P4K-035). */
public enum StarmapLinkAction {

  /** Links the row to a chosen object; no APPLY changes it. */
  SET,

  /** Unlinks the row; no APPLY links it again. */
  CLEAR,

  /** Hands the row's link back to the P4K import, which decides it on the next APPLY. */
  RELEASE
}
