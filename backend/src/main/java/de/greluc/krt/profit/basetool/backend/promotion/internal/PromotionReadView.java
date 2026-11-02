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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

/**
 * Whose promotion data a catalogue read shows: the caller's own squadron, as members see it, or the
 * squadrons the caller manages as an officer (REQ-ROLE-007).
 */
public enum PromotionReadView {

  /** The caller's active squadron; the member-facing catalogue and progress. */
  MEMBER,

  /** The squadrons in the caller's officer reach, narrowed by a pin; the management pages. */
  MANAGEMENT
}
