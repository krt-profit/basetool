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

package de.greluc.krt.profit.basetool.frontend.logging;

import org.jetbrains.annotations.Nullable;

/**
 * Thread-local holder for the current request's correlation id, set and cleared by {@link
 * CorrelationIdFilter}.
 *
 * <p>Restored on Reactor worker threads through the {@code ThreadLocalAccessor} registered in
 * {@link de.greluc.krt.profit.basetool.frontend.config.ReactorContextPropagationConfig}.
 */
public final class CorrelationContext {

  private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

  private CorrelationContext() {}

  /** Stores the given correlation id in the calling thread; a blank value clears the slot. */
  public static void set(@Nullable String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      HOLDER.remove();
    } else {
      HOLDER.set(correlationId);
    }
  }

  /** Returns the correlation id stored for the current thread, or {@code null} if none set. */
  @Nullable
  public static String get() {
    return HOLDER.get();
  }

  /** Removes the stored correlation id - call from {@code finally} blocks to avoid leakage. */
  public static void clear() {
    HOLDER.remove();
  }
}
