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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Decides whether a client's batch removes so much of a member's resource that the member must
 * confirm it in the browser (REQ-XCH-021, ADR-0218).
 *
 * <p>Per client, member and resource over a rolling 24 hours, a batch is held back when it takes
 * the window's removals above {@value #MAX_REMOVALS}, or above one fifth of the current count plus
 * the removals already in the window with at least {@value #MIN_REMOVALS}. The resource's write
 * service decides what in a batch counts as a removal.
 */
@Service
@RequiredArgsConstructor
public class ExchangeMassChangeGuard {

  /** The rolling window the removals are counted over. */
  public static final Duration WINDOW = Duration.ofHours(24);

  /** The most removals a window may hold without a confirmation. */
  static final int MAX_REMOVALS = 25;

  /** The fewest removals the share rule applies to. */
  static final int MIN_REMOVALS = 5;

  private final ExchangeJournalService journalService;
  private final Clock clock = Clock.systemUTC();

  /**
   * Checks one batch before it is applied.
   *
   * @param caller the client and member
   * @param resource the resource the batch writes
   * @param currentCount the member's entries of the resource before the batch
   * @param batchRemovals the batch's removals, counted by the resource's rules
   * @return {@code true} when the member must confirm the batch
   */
  public boolean requiresConfirmation(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeResource resource,
      long currentCount,
      long batchRemovals) {
    if (batchRemovals <= 0) {
      return false;
    }
    long inWindow = journalService.removalsSince(caller, resource, clock.instant().minus(WINDOW));
    return trips(inWindow, batchRemovals, currentCount);
  }

  /**
   * Applies the counting rule.
   *
   * @param inWindow the removals already in the window
   * @param batchRemovals the batch's removals
   * @param currentCount the member's entries before the batch
   * @return {@code true} when the batch takes the window above either limit
   */
  static boolean trips(long inWindow, long batchRemovals, long currentCount) {
    long total = inWindow + batchRemovals;
    if (total > MAX_REMOVALS) {
      return true;
    }
    return total >= MIN_REMOVALS && total * 5 > currentCount + inWindow;
  }
}
