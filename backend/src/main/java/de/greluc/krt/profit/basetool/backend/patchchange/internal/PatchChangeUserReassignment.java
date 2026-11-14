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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The patch change's part of the account merge (REQ-PATCH-022): a home-location choice answers one
 * phase for the account that chose. It stays with that account and goes with it; the surviving
 * account keeps its own choices and, where it has none, is asked like any member.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PatchChangeUserReassignment implements UserReassignmentParticipant {

  static final int POSITION = 220;

  @Override
  public int position() {
    return POSITION;
  }

  @Override
  public @NotNull List<OwnedRows> followsTheMember() {
    return List.of();
  }

  @Override
  public @NotNull List<String> staysWithTheAct() {
    return List.of("patch_change_relocation.user_id");
  }

  @Override
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {}

  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    return List.of();
  }
}
