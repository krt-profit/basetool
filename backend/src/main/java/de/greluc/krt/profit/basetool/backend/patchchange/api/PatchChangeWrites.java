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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Marks the writes of a patch change's trigger, so the exchange change feed attributes every row
 * they touch to the {@code patch} source with that patch change (REQ-PATCH-007).
 *
 * <p>The mark is read when a writing transaction begins, so the trigger opens its transaction
 * inside {@link #runFor}.
 */
public final class PatchChangeWrites {

  /** The patch change whose trigger runs on the current thread. */
  private static final ScopedValue<UUID> CURRENT = ScopedValue.newInstance();

  /** Not instantiable. */
  private PatchChangeWrites() {}

  /**
   * Runs a write as the trigger of a patch change.
   *
   * @param patchChangeId the patch change being triggered
   * @param write the write, which opens its transaction inside this call
   * @param <T> its result
   * @return its result
   */
  public static <T> T runFor(@NotNull UUID patchChangeId, @NotNull Supplier<T> write) {
    return ScopedValue.where(CURRENT, patchChangeId).call(write::get);
  }

  /**
   * Returns the patch change whose trigger runs on the current thread.
   *
   * @return the patch change's id, or empty outside {@link #runFor}
   */
  public static @NotNull Optional<UUID> current() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }
}
