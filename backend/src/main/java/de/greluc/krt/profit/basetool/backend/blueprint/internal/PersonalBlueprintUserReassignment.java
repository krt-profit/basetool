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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The blueprint module's part of the account merge: the personal blueprints follow the member, a
 * product both accounts own is kept once.
 */
@Component
public class PersonalBlueprintUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 50;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public PersonalBlueprintUserReassignment(@NotNull OwnedRowsMover mover) {
    super(
        mover,
        POSITION,
        List.of(OwnedRows.deduped("personal_blueprint", "owner_user_id", "product_key")),
        List.of());
  }
}
