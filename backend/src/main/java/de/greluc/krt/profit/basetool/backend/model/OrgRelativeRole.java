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
 * A role evaluated relative to an org unit carried by an event (used by {@link
 * SelectorKind#ORG_RELATIVE_ROLE}).
 *
 * <p>{@link #OFFICER} is the global {@code OFFICER} role intersected with membership of the context
 * org unit; the others read the corresponding per-membership flag of the context org unit.
 */
public enum OrgRelativeRole {

  /** Holders of the global {@code OFFICER} role who are members of the context org unit. */
  OFFICER,

  /** Members flagged as Lead of the context org unit (only Spezialkommandos carry leads). */
  LEAD,

  /** Members flagged as Logistician of the context org unit. */
  LOGISTICIAN,

  /** Members flagged as Mission Manager of the context org unit. */
  MISSION_MANAGER,

  /**
   * Members whose rank confers a leadership seat in the context org unit (Staffelleiter,
   * Bereichsleiter, …), independent of the global {@code OFFICER} role.
   */
  UNIT_LEADERSHIP
}
