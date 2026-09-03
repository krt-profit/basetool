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
 * Carries the names of the session attributes that {@link SessionAttributeDiagnosticMapper} had to
 * drop on the current thread, from the session <em>read</em> to {@link
 * SessionAttributeRepairFilter} which repairs them on the same request (REQ-SEC-050).
 *
 * <p><strong>Why a hand-off is needed at all.</strong> The mapper is the only layer that sees which
 * hash field failed, and it is deliberately read-only — repairing from inside the deserializer
 * would mean writing to Redis from the session read path, which is the subsystem that took the
 * whole application down twice inside two releases (ADR-0154). The filter, by contrast, repairs
 * through {@code HttpSession#removeAttribute}, the same public API that {@code
 * BackendRoleSyncFilter} and {@code TermsAcceptanceGateFilter} already use on every request. All
 * this class does is get the attribute name from the one to the other; the repository sits in
 * between and offers no seam.
 *
 * <p>A thread-local rather than a request attribute because the session is loaded lazily, deep
 * inside {@code SessionRepositoryFilter}'s wrapper, where no {@code HttpServletRequest} of ours is
 * in scope. The idiom matches {@code ActiveSquadronContext} and {@code CorrelationContext}, which
 * cross the same kind of gap.
 *
 * <p><strong>Bounded and cleared on both edges.</strong> Tomcat pools request threads, so a name
 * left behind would be applied to the <em>next</em> request's session — a different member's.
 * {@link SessionAttributeRepairFilter} therefore clears the queue before it enters the chain as
 * well as draining it on the way out, and {@link #MAX_PENDING} caps what a thread that never
 * reaches the filter (the repository's keyspace-notification listener) can accumulate.
 */
public final class SessionAttributeRepairQueue {

  /**
   * Largest number of attribute names held for one thread.
   *
   * <p>A session hash carries a handful of attributes and only the unreadable ones land here, so
   * the cap is never reached in practice. It exists because the key is a name read out of Redis and
   * because a thread that never passes through {@link SessionAttributeRepairFilter} never drains —
   * an unbounded set on either count is a slow leak with a patient trigger.
   */
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
   * Discards anything left on this thread without repairing it.
   *
   * <p>Called on the way <em>into</em> the filter chain: a name that survived a previous request on
   * this pooled thread belongs to a session that is no longer the current one, and applying it here
   * would remove an attribute from the wrong member's session.
   */
  static void clear() {
    PENDING.remove();
  }
}
