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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreatorRetention;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ages closed job orders out of the exchange's client set (REQ-OXCH-003, ADR-0406) and clears the
 * creator of the orders outside it (REQ-OXCH-010).
 *
 * <p>A run marks every fingerprinted order whose 7 days after closing ended, and that is no aUEC
 * order with an open settlement, as having left the set, and the fingerprint row's trigger
 * announces each marked order to the units and members it was last announced to, so a feed page
 * resolves it to a tombstone. An order whose last fingerprint was written outside the set was
 * announced by that write and is never marked, so a rerun announces nothing. The same transaction
 * clears the creating member and key of every order outside the set. One backend instance at a time
 * runs, under {@link ExchangeJobOrderFingerprintRepository#tryWindowLock()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrderWindowRun {

  private final ExchangeJobOrderFingerprintRepository fingerprints;
  private final JobOrderCreatorRetention creators;
  private final Clock clock = Clock.systemUTC();

  /**
   * Ages out the orders whose window ended since their last announcement and clears the creators of
   * the orders outside the set.
   *
   * @return the number of orders aged out, {@code 0} when another instance holds the run's lock
   */
  @Transactional
  public int ageOut() {
    if (!fingerprints.tryWindowLock()) {
      return 0;
    }
    Instant now = clock.instant();
    Instant closedSince = now.minus(ExchangeJobOrderReadService.CLOSED_WINDOW);
    int agedOut = fingerprints.markLeftSet(closedSince, now);
    int cleared =
        creators.clearCreators(fingerprints.findOutsideWithCreator(closedSince), closedSince);
    log.info(
        "Job-order window: {} orders aged out of the client set, {} creators cleared.",
        agedOut,
        cleared);
    return agedOut;
  }
}
