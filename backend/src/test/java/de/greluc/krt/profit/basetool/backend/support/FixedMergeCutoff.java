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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockMergeCutoff;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * A merge cutoff that answers the read and the write alike from one function, for a test that runs
 * no patch change.
 *
 * @param answer the cutoff per holder
 */
public record FixedMergeCutoff(@NotNull Function<UUID, Optional<Instant>> answer)
    implements StockMergeCutoff {

  /**
   * A cutoff that never stands, so every row merges.
   *
   * @return the cutoff
   */
  public static @NotNull FixedMergeCutoff none() {
    return new FixedMergeCutoff(_ -> Optional.empty());
  }

  @Override
  public @NotNull Optional<Instant> cutoffFor(@NotNull UUID holderId) {
    return answer.apply(holderId);
  }

  @Override
  public @NotNull Optional<Instant> cutoffForWrite(@NotNull UUID holderId) {
    return answer.apply(holderId);
  }
}
