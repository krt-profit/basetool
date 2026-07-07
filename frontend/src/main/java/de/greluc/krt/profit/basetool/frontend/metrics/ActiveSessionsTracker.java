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

package de.greluc.krt.profit.basetool.frontend.metrics;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe live count of active Spring Session sessions, maintained from session-lifecycle
 * events rather than by scanning the session store on every Prometheus scrape.
 *
 * <p><b>Why this exists:</b> the {@code basetool_active_sessions} gauge previously sampled {@code
 * SpringSessionBackedSessionRegistry.getAllPrincipals()}, whose Redis-backed implementation
 * unconditionally throws {@code UnsupportedOperationException}. Micrometer swallowed that throwable
 * during sampling and reported {@code NaN} on every scrape, so the gauge never produced a value and
 * the {@code SsePushChannelDead} alert — which gates on {@code basetool_active_sessions > 3} —
 * could never fire, silently disarming the exact "push dead while users online" detection it was
 * built for (#1158). This tracker instead holds the set of live session ids: it is seeded once at
 * startup from the Redis session namespace and then kept current by {@code SessionCreatedEvent} /
 * {@code SessionDeletedEvent} / {@code SessionExpiredEvent} (all published by {@code
 * RedisIndexedSessionRepository}).
 *
 * <p>Membership is keyed on the session id, so start/end are idempotent: a session end that fires
 * both a delete and an expire event, or a create id that also appears in the seed set, never
 * double-counts, and {@link #count()} can never go negative. The backing set is a {@link
 * ConcurrentHashMap#newKeySet() concurrent set} because the startup seed, the Redis
 * keyspace-notification listener threads, and the scrape all touch it concurrently.
 */
public final class ActiveSessionsTracker {

  /** Ids of the sessions currently considered live; sized by concurrent users, so cheap to hold. */
  private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();

  /**
   * Records a session as active. Idempotent: re-adding an id already tracked (e.g. a create event
   * for a session already present from the startup seed) is a no-op.
   *
   * @param sessionId the id of the started session; a {@code null}/blank id is ignored.
   */
  public void onSessionStarted(@Nullable String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      activeSessionIds.add(sessionId);
    }
  }

  /**
   * Records a session as no longer active. Idempotent: removing an id that is not tracked (e.g. a
   * second end event for the same session, or an expiry of a session created before startup that
   * the seed missed) is a no-op and never drives the count below zero.
   *
   * @param sessionId the id of the ended session; a {@code null} id is ignored.
   */
  public void onSessionEnded(@Nullable String sessionId) {
    if (sessionId != null) {
      activeSessionIds.remove(sessionId);
    }
  }

  /**
   * Seeds the tracker with the ids of sessions that already existed before this instance started
   * listening — without them a post-restart gauge would read ~0 until existing sessions cycle,
   * leaving the {@code SsePushChannelDead} alert under-armed for up to the session TTL. Each id is
   * added through {@link #onSessionStarted(String)}, so seeding is idempotent and
   * order-independent.
   *
   * @param sessionIds the pre-existing live session ids; an empty collection is a valid no-op.
   */
  public void seed(Collection<String> sessionIds) {
    for (String id : sessionIds) {
      onSessionStarted(id);
    }
  }

  /**
   * Returns the number of sessions currently tracked as active — the value sampled by the {@code
   * basetool_active_sessions} gauge on each scrape.
   *
   * @return the current active-session count, never negative.
   */
  public long count() {
    return activeSessionIds.size();
  }
}
