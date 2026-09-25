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

package de.greluc.krt.profit.basetool.frontend.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Thread-local hand-off of the session attribute names {@link SessionAttributeDiagnosticMapper}
 * dropped, to {@link SessionAttributeRepairFilter}, which removes them on the same request
 * (REQ-SEC-050).
 *
 * <p>Cleared on entry to and drained on exit from the filter chain, and capped at {@link
 * #MAX_PENDING}, so a name never reaches another request's session.
 */
public final class SessionAttributeRepairQueue {

  /** Largest number of attribute names held for one thread, bounding a thread that never drains. */
  private static final int MAX_PENDING = 16;

  /** Attribute names dropped on this thread and not yet repaired; absent when nothing failed. */
  private static final ThreadLocal<Set<String>> PENDING = new ThreadLocal<>();

  /** Not instantiable: this is a thread-local hand-off, not a component. */
  private SessionAttributeRepairQueue() {}

  /**
   * Notes that {@code attributeName} was dropped and needs repairing on this request.
   *
   * @param attributeName the session attribute name (never the hash field, never a value).
   */
  static void record(@NotNull String attributeName) {
    Set<String> pending = PENDING.get();
    if (pending == null) {
      pending = new LinkedHashSet<>();
      PENDING.set(pending);
    }
    if (pending.size() < MAX_PENDING) {
      pending.add(attributeName);
    }
  }

  /**
   * Takes the pending names and empties the queue.
   *
   * @return the attribute names to repair; empty when nothing was dropped.
   */
  @NotNull
  static Set<String> drain() {
    Set<String> pending = PENDING.get();
    PENDING.remove();
    return pending == null ? Set.of() : pending;
  }

  /**
   * Discards anything left on this thread without repairing it; called on entry to the filter
   * chain.
   */
  static void clear() {
    PENDING.remove();
  }
}
