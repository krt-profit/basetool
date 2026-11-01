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

package de.greluc.krt.profit.basetool.frontend.identity.model;

/** What a member's rank range follows from, as the backend names it (REQ-ROLE-007). */
public enum RankRangeBasis {
  /** The member holds {@code ADMIN}: any rank. */
  ADMIN,
  /** The member holds officer reach in at least one org unit. */
  LEADERSHIP_SEAT,
  /** The member holds {@code KRT Member} without officer reach. */
  KRT_MEMBER
}
