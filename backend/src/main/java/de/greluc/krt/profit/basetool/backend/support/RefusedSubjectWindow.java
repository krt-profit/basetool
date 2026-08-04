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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * Counts how many <em>distinct</em> subjects were refused within a sliding time window.
 *
 * <p>Exists because counting refusal <em>requests</em> cannot distinguish "many people are locked
 * out" from "one client is retrying". That distinction is the whole content of the {@code
 * TermsConsentRolloutStalled} alert: a single looping browser tab, a single member reading the
 * terms slowly, and a single straggler whose desktop extractor keeps retrying all produce an
 * unbounded refusal <em>rate</em> while being, in every case, one person who is not locked out by a
 * broken consent path. On 2026-08-03 exactly that misreading fired the alert twice overnight
 * (REQ-SEC-028, REQ-OBS-011).
 *
 * <p>Bounded by construction, because it is fed by an internet-reachable surface: entries expire
 * with the window, and once {@link #maxTracked} distinct subjects are held a further <em>new</em>
 * subject is dropped rather than admitted. Dropping loses precision in the only direction that is
 * safe — the gauge under-reports, so a cap can never manufacture an alert — and the cap sits far
 * above any real membership, so reaching it already means something other than a rollout.
 *
 * <p>Not persisted and not shared between instances. Each process reports what it saw, which is why
 * the alert reads the series with {@code max()} rather than {@code sum()}: a subject refused on two
 * instances must not count twice.
 */
public final class RefusedSubjectWindow {

  private final Map<UUID, Long> lastSeenBySubject = new ConcurrentHashMap<>();
  private final long windowMillis;
  private final int maxTracked;
  private final LongSupplier clock;

  /**
   * Creates a window over the wall clock.
   *
   * @param window how long a subject stays counted after its most recent refusal
   * @param maxTracked hard cap on distinct subjects held at once
   */
  public RefusedSubjectWindow(@NotNull Duration window, int maxTracked) {
    this(window, maxTracked, System::currentTimeMillis);
  }

  /**
   * Creates a window over an injectable clock, so a test can advance time without sleeping.
   *
   * @param window how long a subject stays counted after its most recent refusal
   * @param maxTracked hard cap on distinct subjects held at once
   * @param clock supplies the current epoch milliseconds
   */
  RefusedSubjectWindow(@NotNull Duration window, int maxTracked, @NotNull LongSupplier clock) {
    this.windowMillis = window.toMillis();
    this.maxTracked = maxTracked;
    this.clock = clock;
  }

  /**
   * Records that {@code subject} was refused now, refreshing it if it is already held.
   *
   * <p>A subject already in the window is always refreshed even at the cap: the cap exists to bound
   * growth from <em>new</em> subjects, and refusing to refresh a known one would let it expire
   * while it is still actively being refused.
   *
   * @param subject the refused caller
   */
  public void record(@NotNull UUID subject) {
    long now = clock.getAsLong();
    if (lastSeenBySubject.containsKey(subject) || lastSeenBySubject.size() < maxTracked) {
      lastSeenBySubject.put(subject, now);
      return;
    }
    // At the cap with a subject we have not seen. Prune first — the cap is usually reached by stale
    // entries, not by live ones — and admit it only if that freed room.
    prune(now);
    if (lastSeenBySubject.size() < maxTracked) {
      lastSeenBySubject.put(subject, now);
    }
  }

  /**
   * Reports how many distinct subjects were refused within the window, dropping expired entries.
   *
   * <p>Pruning on read rather than on a timer keeps this allocation-free between scrapes and means
   * the value is always computed against the instant it is read.
   *
   * @return the number of distinct subjects currently inside the window
   */
  public int size() {
    long now = clock.getAsLong();
    prune(now);
    return lastSeenBySubject.size();
  }

  /**
   * Removes every subject whose most recent refusal fell out of the window.
   *
   * @param now the current epoch milliseconds
   */
  private void prune(long now) {
    lastSeenBySubject.entrySet().removeIf(entry -> now - entry.getValue() >= windowMillis);
  }
}
