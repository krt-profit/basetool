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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Process-wide lock ensuring the UEX and SC Wiki syncs never run at the same time.
 *
 * <p>A fair {@link ReentrantLock} with a timed wait: a blocked sync waits for the running one, up
 * to {@code krt.sync.coordinator.max-wait-ms} (default 1 h), and is skipped if the cap elapses.
 */
@Slf4j
@Component
public class SyncCoordinator {

  /**
   * Fair gate so a blocked tick acquires the lock in FIFO order the instant the holder releases.
   */
  private final ReentrantLock lock = new ReentrantLock(true);

  /**
   * How long a blocked tick waits for the in-flight sync before giving up (hung-sync safety net).
   */
  private final long maxWaitMillis;

  /**
   * Label of the sync currently holding the lock, for the wait/skip log lines; {@code null} idle.
   */
  private volatile String activeLabel;

  /**
   * Creates the coordinator with the configured maximum wait for a blocked tick.
   *
   * @param maxWaitMillis upper bound, in milliseconds, that a blocked sync waits for the in-flight
   *     sync to finish before skipping its own run; from {@code krt.sync.coordinator.max-wait-ms}
   *     (default {@code 3600000} = 1 h)
   */
  public SyncCoordinator(@Value("${krt.sync.coordinator.max-wait-ms:3600000}") long maxWaitMillis) {
    this.maxWaitMillis = maxWaitMillis;
  }

  /**
   * Runs {@code task} under the shared lock, waiting up to the configured cap for a running sync to
   * finish; the lock is released even if the task throws.
   *
   * @param label short human-readable name of the sync ({@code "UEX"} / {@code "SC Wiki"}) used in
   *     the wait/skip log lines and to report which sync is holding the gate
   * @param task the sync sweep to run under the exclusive lock
   * @return {@code true} if the task ran, {@code false} if the wait cap elapsed (or the thread was
   *     interrupted) before the gate could be acquired
   */
  public boolean runExclusively(String label, Runnable task) {
    String holderAtEntry = activeLabel;
    if (holderAtEntry != null) {
      log.info(
          "{} sync is waiting: the {} sync is still running — will start as soon as it finishes.",
          label,
          holderAtEntry);
    }
    boolean acquired;
    try {
      acquired = lock.tryLock(maxWaitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting to start {} sync — skipping this run.", label);
      return false;
    }
    if (!acquired) {
      log.warn(
          "Skipping {} sync: the {} sync did not finish within {} ms — not starting a concurrent"
              + " run; the next daily tick will retry.",
          label,
          activeLabel == null ? "previous" : activeLabel,
          maxWaitMillis);
      return false;
    }
    try {
      activeLabel = label;
      task.run();
      return true;
    } finally {
      activeLabel = null;
      lock.unlock();
    }
  }
}
