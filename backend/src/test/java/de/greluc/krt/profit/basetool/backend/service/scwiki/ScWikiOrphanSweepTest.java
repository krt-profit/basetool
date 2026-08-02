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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Unit tests for {@link ScWikiOrphanSweep}, pinning the two load-bearing data-safety gates that
 * keep a partial run from tombstoning an entire SC-Wiki catalogue table: the sweep must never be
 * invoked with an empty seen-set, and never after a page walk that could not enumerate the whole
 * feed (H5 — a half-walked feed still yields hundreds of real UUIDs, so the emptiness gate alone
 * waves it through and everything on the un-fetched pages gets soft-deleted).
 */
class ScWikiOrphanSweepTest {

  private final Logger log = mock(Logger.class);

  @Test
  void emptySeen_neverInvokesSweep_andDoesNotLog() {
    AtomicInteger sweepCalls = new AtomicInteger();

    ScWikiOrphanSweep.sweepDeletedOrphans(
        Set.of(),
        true,
        seen -> {
          sweepCalls.incrementAndGet();
          return 5;
        },
        log,
        "ship_type");

    assertThat(sweepCalls.get()).as("empty seen must not run the tombstone sweep").isZero();
    verifyNoInteractions(log);
  }

  @Test
  void nonEmptySeen_zeroMarked_invokesSweepWithTheSeenSet_butDoesNotLog() {
    Set<UUID> seen = Set.of(UUID.randomUUID());
    AtomicReference<Set<UUID>> passedToSweep = new AtomicReference<>();

    ScWikiOrphanSweep.sweepDeletedOrphans(
        seen,
        true,
        s -> {
          passedToSweep.set(s);
          return 0;
        },
        log,
        "manufacturer");

    assertThat(passedToSweep.get())
        .as("the sweep must receive the run's own seen-set as its exclusion set")
        .isSameAs(seen);
    verifyNoInteractions(log);
  }

  @Test
  void nonEmptySeen_someMarked_logsCountAndEntityLabel() {
    Set<UUID> seen = Set.of(UUID.randomUUID());

    ScWikiOrphanSweep.sweepDeletedOrphans(seen, true, s -> 3, log, "ship_type");

    verify(log).info("Marked {} {} row(s) scwiki_deleted (no longer in Wiki feed)", 3, "ship_type");
  }

  @Test
  void incompleteFetch_refusesToSweep_andWarnsWhy() {
    // The regression this gate exists for: a page walk that died after page 1 still hands the sync
    // a non-empty seen-set, so the emptiness gate passes and every row on the pages that were never
    // fetched would be tombstoned for never having been fetched.
    Set<UUID> seen = Set.of(UUID.randomUUID(), UUID.randomUUID());
    AtomicInteger sweepCalls = new AtomicInteger();

    ScWikiOrphanSweep.sweepDeletedOrphans(
        seen,
        false,
        s -> {
          sweepCalls.incrementAndGet();
          return 200;
        },
        log,
        "ship_type");

    assertThat(sweepCalls.get())
        .as("an incomplete page walk must not run the tombstone sweep")
        .isZero();
    verify(log)
        .warn(
            "Skipping the {} scwiki_deleted sweep: the Wiki page walk did not enumerate the whole"
                + " feed this run, so the {} uuid(s) it saw are not a complete census and every row"
                + " outside them would be tombstoned for never having been fetched.",
            "ship_type",
            2);
  }
}
