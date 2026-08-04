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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the distinct-subject window behind {@code basetool_terms_refused_subjects}
 * (REQ-SEC-028, REQ-OBS-011).
 *
 * <p>{@link #countsOneRetryingSubjectAsOne} is the case the metric exists for. Counting refusal
 * requests instead fired {@code TermsConsentRolloutStalled} twice overnight on 2026-08-03 against a
 * single browser tab in a reconnect loop — the alert could not tell a locked-out membership from
 * one client retrying, and at night the difference is the entire signal.
 */
class RefusedSubjectWindowTest {

  private final AtomicLong now = new AtomicLong(1_000_000L);

  private RefusedSubjectWindow window(Duration windowLength, int maxTracked) {
    return new RefusedSubjectWindow(windowLength, maxTracked, now::get);
  }

  /** One subject refused any number of times is one subject. */
  @Test
  void countsOneRetryingSubjectAsOne() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 100);
    UUID subject = UUID.randomUUID();

    for (int i = 0; i < 500; i++) {
      window.record(subject);
    }

    assertThat(window.size()).isEqualTo(1);
  }

  /** Distinct subjects accumulate — that is the number the alert thresholds on. */
  @Test
  void countsDistinctSubjectsSeparately() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 100);

    window.record(UUID.randomUUID());
    window.record(UUID.randomUUID());
    window.record(UUID.randomUUID());

    assertThat(window.size()).isEqualTo(3);
  }

  /** A subject leaves the window once its most recent refusal ages out. */
  @Test
  void dropsASubjectOnceTheWindowPasses() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 100);
    window.record(UUID.randomUUID());

    now.addAndGet(Duration.ofMinutes(14).toMillis());
    assertThat(window.size()).as("still inside the window").isEqualTo(1);

    now.addAndGet(Duration.ofMinutes(1).toMillis());
    assertThat(window.size()).as("aged out").isZero();
  }

  /**
   * A subject that keeps being refused keeps its place, rather than expiring mid-incident.
   *
   * <p>The window measures "refused recently", not "first refused recently" — a member who is still
   * locked out an hour into a stalled rollout must still be counted.
   */
  @Test
  void refreshesASubjectThatIsStillBeingRefused() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 100);
    UUID subject = UUID.randomUUID();
    window.record(subject);

    now.addAndGet(Duration.ofMinutes(14).toMillis());
    window.record(subject);
    now.addAndGet(Duration.ofMinutes(14).toMillis());

    assertThat(window.size()).isEqualTo(1);
  }

  /**
   * The cap bounds the map and under-reports rather than over-reports.
   *
   * <p>The feed is an internet-reachable refusal path, so the map must not grow without bound. The
   * direction matters: a cap that dropped <em>old</em> entries in favour of new ones could hold the
   * gauge at the cap forever and manufacture an alert. Dropping the new one cannot.
   */
  @Test
  void staysBoundedAtTheCapWithoutOverReporting() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 3);

    for (int i = 0; i < 50; i++) {
      window.record(UUID.randomUUID());
    }

    assertThat(window.size()).isEqualTo(3);
  }

  /** Once capped entries expire, the window admits new subjects again. */
  @Test
  void admitsNewSubjectsAgainAfterTheCappedOnesExpire() {
    RefusedSubjectWindow window = window(Duration.ofMinutes(15), 2);
    window.record(UUID.randomUUID());
    window.record(UUID.randomUUID());
    assertThat(window.size()).isEqualTo(2);

    now.addAndGet(Duration.ofMinutes(16).toMillis());
    window.record(UUID.randomUUID());

    assertThat(window.size()).isEqualTo(1);
  }
}
