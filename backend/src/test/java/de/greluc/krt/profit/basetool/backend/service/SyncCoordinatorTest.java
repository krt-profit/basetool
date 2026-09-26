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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SyncCoordinator} — the cross-scheduler mutual-exclusion gate. */
class SyncCoordinatorTest {

  /** A generous wait so the "blocked tick waits then runs" path never trips the timeout. */
  private static final long GENEROUS_WAIT_MS = 5_000;

  @Test
  void runExclusively_runsTask_andReturnsTrue_whenGateIsFree() {
    SyncCoordinator coordinator = new SyncCoordinator(GENEROUS_WAIT_MS);
    AtomicBoolean ran = new AtomicBoolean(false);

    boolean result = coordinator.runExclusively("UEX", () -> ran.set(true));

    assertTrue(result, "an uncontended call must run");
    assertTrue(ran.get(), "the task must have executed");
  }

  @Test
  void runExclusively_releasesGate_soSubsequentCallsRun() {
    SyncCoordinator coordinator = new SyncCoordinator(GENEROUS_WAIT_MS);
    AtomicInteger runs = new AtomicInteger();

    coordinator.runExclusively("UEX", runs::incrementAndGet);
    coordinator.runExclusively("SC Wiki", runs::incrementAndGet);

    assertEquals(2, runs.get(), "the gate must be released after each run");
  }

  @Test
  void runExclusively_releasesGate_evenWhenTaskThrows() {
    SyncCoordinator coordinator = new SyncCoordinator(GENEROUS_WAIT_MS);

    assertThrows(
        RuntimeException.class,
        () ->
            coordinator.runExclusively(
                "UEX",
                () -> {
                  throw new RuntimeException("boom");
                }));

    AtomicBoolean ranAfter = new AtomicBoolean(false);
    boolean result = coordinator.runExclusively("SC Wiki", () -> ranAfter.set(true));

    assertTrue(result, "a thrown task must still release the gate");
    assertTrue(ranAfter.get(), "the next sync must run after a failed one releases the gate");
  }

  @Test
  void runExclusively_waitsForTheRunningSync_thenRuns() throws InterruptedException {
    SyncCoordinator coordinator = new SyncCoordinator(GENEROUS_WAIT_MS);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Thread holder =
        new Thread(
            () ->
                coordinator.runExclusively(
                    "UEX",
                    () -> {
                      holding.countDown();
                      try {
                        release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }),
            "gate-holder");
    holder.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS), "holder thread must acquire the gate first");

    AtomicBoolean secondRan = new AtomicBoolean(false);
    Thread waiter =
        new Thread(
            () -> coordinator.runExclusively("SC Wiki", () -> secondRan.set(true)), "gate-waiter");
    waiter.start();

    Thread.sleep(200);
    assertFalse(secondRan.get(), "the waiting sync must not run while the gate is held");
    assertTrue(waiter.isAlive(), "the waiting sync must still be queued, not dropped");

    release.countDown();
    waiter.join(5_000);
    holder.join(5_000);
    assertTrue(secondRan.get(), "the queued sync must run once the holder releases the gate");
  }

  @Test
  void runExclusively_skipsTask_whenWaitCapElapsesBecauseHolderIsStuck()
      throws InterruptedException {
    SyncCoordinator coordinator = new SyncCoordinator(100);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Thread holder =
        new Thread(
            () ->
                coordinator.runExclusively(
                    "UEX",
                    () -> {
                      holding.countDown();
                      try {
                        release.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }),
            "gate-holder");
    holder.start();
    assertTrue(holding.await(5, TimeUnit.SECONDS), "holder thread must acquire the gate first");

    AtomicBoolean secondRan = new AtomicBoolean(false);
    boolean result = coordinator.runExclusively("SC Wiki", () -> secondRan.set(true));

    assertFalse(result, "a call that waits past the cap must be skipped");
    assertFalse(secondRan.get(), "the skipped task must not run");

    release.countDown();
    holder.join(5_000);
  }
}
