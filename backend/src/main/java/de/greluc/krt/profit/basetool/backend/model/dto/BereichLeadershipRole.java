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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * The role a member holds within a Bereichsleitung (REQ-ORG-017), each mapping to one flag on the
 * member's {@code org_unit_membership} row. All three confer the same cascading officer-equivalent
 * reach (REQ-ORG-015).
 */
public enum BereichLeadershipRole {

  /** Bereichsleiter — the head of the Bereich ({@code is_bereichsleiter}). */
  LEITER,

  /** Bereichskoordinator — an area coordinator ({@code is_bereichskoordinator}). */
  KOORDINATOR,

  /** Bereichsoperator — an area operator ({@code is_bereichsoperator}). */
  OPERATOR
}
