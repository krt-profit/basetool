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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The mission module's part of the account merge: owned missions with their ownership companions,
 * party leads, unit responsibilities, manager seats and participations follow the member.
 */
@Component
public class MissionUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 90;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   */
  public MissionUserReassignment(@NotNull OwnedRowsMover mover) {
    super(
        mover,
        POSITION,
        List.of(
            OwnedRows.of("mission", "owner_id"),
            OwnedRows.of("mission_ownership", "owner_id"),
            OwnedRows.of("mission", "party_lead_user_id"),
            OwnedRows.of("mission_unit", "responsible_user_id"),
            OwnedRows.deduped("mission_managers", "user_id", "mission_id"),
            OwnedRows.deduped("mission_participant", "user_id", "mission_id")),
        List.of());
  }
}
