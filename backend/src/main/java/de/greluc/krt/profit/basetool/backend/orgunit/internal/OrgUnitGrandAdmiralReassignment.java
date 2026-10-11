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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The org-unit module's grand-admiral part of the account merge: the appointment follows the
 * member.
 */
@Component
public class OrgUnitGrandAdmiralReassignment extends OwnedRowsReassignment {

  static final int POSITION = 80;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public OrgUnitGrandAdmiralReassignment(@NotNull OwnedRowsMover mover) {
    super(mover, POSITION, List.of(OwnedRows.of("org_unit", "grand_admiral_user_id")), List.of());
  }
}
