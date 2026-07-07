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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActiveSessionsTracker}, which backs the {@code basetool_active_sessions}
 * gauge (#1158). The behaviour that matters for the {@code SsePushChannelDead} alert is that the
 * count is finite, never negative, and idempotent under the duplicate / out-of-order lifecycle
 * events the Redis-backed session store can deliver.
 */
class ActiveSessionsTrackerTest {

  private final ActiveSessionsTracker tracker = new ActiveSessionsTracker();

  @Test
  void countsDistinctStartedSessions() {
    tracker.onSessionStarted("s1");
    tracker.onSessionStarted("s2");

    assertThat(tracker.count()).isEqualTo(2L);
  }

  @Test
  void startIsIdempotent() {
    tracker.onSessionStarted("s1");
    tracker.onSessionStarted("s1");

    assertThat(tracker.count()).isEqualTo(1L);
  }

  @Test
  void endRemovesAStartedSession() {
    tracker.onSessionStarted("s1");
    tracker.onSessionStarted("s2");

    tracker.onSessionEnded("s1");

    assertThat(tracker.count()).isEqualTo(1L);
  }

  @Test
  void endIsIdempotentAndNeverGoesNegative() {
    tracker.onSessionStarted("s1");

    // A session end delivered twice (a delete AND an expire event for the same session) must not
    // drive the count below zero.
    tracker.onSessionEnded("s1");
    tracker.onSessionEnded("s1");
    tracker.onSessionEnded("never-tracked");

    assertThat(tracker.count()).isZero();
  }

  @Test
  void seedAddsPreExistingSessionsAndIsIdempotentWithLiveEvents() {
    tracker.seed(List.of("s1", "s2", "s3"));
    // A create event for a session already present in the seed must not double-count it.
    tracker.onSessionStarted("s2");

    assertThat(tracker.count()).isEqualTo(3L);
  }

  @Test
  void nullAndBlankIdsAreIgnored() {
    tracker.onSessionStarted(null);
    tracker.onSessionStarted("   ");
    tracker.onSessionEnded(null);

    assertThat(tracker.count()).isZero();
  }

  @Test
  void newTrackerCountsZero() {
    assertThat(tracker.count()).isZero();
  }
}
