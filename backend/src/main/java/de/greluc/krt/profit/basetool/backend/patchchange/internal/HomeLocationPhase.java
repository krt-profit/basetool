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

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The open home-location phase as its readers need it (REQ-PATCH-024), detached from the record.
 *
 * @param id the patch change that opened it
 * @param label its label
 * @param liveAt its live time, the cutoff of who is asked and what is locked and moves
 */
public record HomeLocationPhase(@NotNull UUID id, @NotNull String label, @NotNull Instant liveAt) {

  /**
   * Takes the phase's facts from its record.
   *
   * @param record the patch change
   * @return the phase
   */
  static @NotNull HomeLocationPhase of(@NotNull PatchChange record) {
    return new HomeLocationPhase(record.getId(), record.getLabel(), record.getLiveAt());
  }
}
