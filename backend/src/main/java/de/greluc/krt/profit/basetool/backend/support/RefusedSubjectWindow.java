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
 * Counts how many distinct subjects were refused within a sliding time window (REQ-SEC-028,
 * REQ-OBS-011).
 *
 * <p>Bounded: entries expire with the window, and beyond {@link #maxTracked} subjects a new one is
 * dropped. Per process, not shared between instances.
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
   * Records that {@code subject} was refused now, refreshing it if it is already held; a known
   * subject is refreshed even at the cap.
   *
   * @param subject the refused caller
   */
  public void record(@NotNull UUID subject) {
    long now = clock.getAsLong();
    if (lastSeenBySubject.containsKey(subject) || lastSeenBySubject.size() < maxTracked) {
      lastSeenBySubject.put(subject, now);
      return;
    }
    prune(now);
    if (lastSeenBySubject.size() < maxTracked) {
      lastSeenBySubject.put(subject, now);
    }
  }

  /**
   * Reports how many distinct subjects were refused within the window, pruning expired entries
   * first.
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
