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
 * Thread-local holder for the current request's correlation id, so that non-servlet components
 * (e.g. the {@code WebClientLoggingFilter}) can propagate the id towards the backend without
 * re-reading the original {@code HttpServletRequest}. Populated by {@link CorrelationIdFilter} at
 * the beginning of every request and cleared in the matching {@code finally} block.
 *
 * <p>Reactor pipelines do <strong>not</strong> copy classic {@code ThreadLocal} values onto their
 * worker threads automatically — the holder only lives on the thread that called {@link #set}. The
 * {@link de.greluc.krt.profit.basetool.frontend.config.ReactorContextPropagationConfig} hooks
 * Reactor's automatic context propagation to a registered {@code ThreadLocalAccessor} so this
 * holder is restored on whichever Reactor worker thread runs the downstream operator. See the
 * {@code CORRELATION_CONTEXT_KEY} constant on that config class for the registry key. Without that
 * wiring the value would be invisible inside {@code WebClient} exchange filters and outbound
 * backend calls would log a different correlation id than the inbound frontend request, breaking
 * the audit-trail join.
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
