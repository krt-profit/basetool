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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockMergeCutoff;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The no-mixing rule's answer to the Lager (REQ-PATCH-004, ADR-0381): a holder's rows from before
 * the open home-location phase's live time never merge while that holder's relocation is pending in
 * it. The wipe runs at the trigger and needs no window of its own.
 */
@Component
@RequiredArgsConstructor
class PatchChangeMergeCutoff implements StockMergeCutoff {

  private final HomeLocationPhases phases;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Optional<Instant> cutoffFor(@NotNull UUID holderId) {
    return cutoffIn(phases.open().orElse(null), holderId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Optional<Instant> cutoffForWrite(@NotNull UUID holderId) {
    return cutoffIn(phases.openForWrite().orElse(null), holderId);
  }

  /**
   * Returns a phase's live time while a holder's relocation is pending in it.
   *
   * @param phase the open phase, or {@code null} while none is open
   * @param holderId the holder
   * @return the live time, or empty when no phase is open or the holder is not pending in it
   */
  private @NotNull Optional<Instant> cutoffIn(
      @Nullable HomeLocationPhase phase, @NotNull UUID holderId) {
    if (phase == null || phases.pendingAmong(phase, List.of(holderId)).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(phase.liveAt());
  }
}
