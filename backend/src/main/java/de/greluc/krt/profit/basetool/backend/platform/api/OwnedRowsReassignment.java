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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The base of a module's {@link UserReassignmentParticipant} that only declares its columns: the
 * tables that follow the member, moved by the {@link OwnedRowsMover}, and the columns that stay
 * with the act. A module that must also refuse a merge overrides {@link #assertMergeable}.
 */
@Transactional(propagation = Propagation.MANDATORY)
public abstract class OwnedRowsReassignment implements UserReassignmentParticipant {

  private final OwnedRowsMover mover;
  private final int position;
  private final List<OwnedRows> follows;
  private final List<String> stays;

  /**
   * Declares a participant.
   *
   * @param mover the engine that runs the statements
   * @param position where the participant runs, unique among all participants
   * @param follows the module's columns that follow the member, in the order they are moved
   * @param stays the module's member-referencing columns that stay with the act
   */
  protected OwnedRowsReassignment(
      @NotNull OwnedRowsMover mover,
      int position,
      @NotNull List<OwnedRows> follows,
      @NotNull List<String> stays) {
    this.mover = mover;
    this.position = position;
    this.follows = List.copyOf(follows);
    this.stays = List.copyOf(stays);
  }

  @Override
  public int position() {
    return position;
  }

  @Override
  public @NotNull List<OwnedRows> followsTheMember() {
    return follows;
  }

  @Override
  public @NotNull List<String> staysWithTheAct() {
    return stays;
  }

  @Override
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {}

  @Override
  public @NotNull List<Moved> reassign(@NotNull UUID source, @NotNull UUID target) {
    return mover.move(follows, source, target);
  }
}
