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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Collects bounded counters for the catalogue attempt running on the calling thread. */
@Component
public class SyncRunProgress {

  private final ThreadLocal<Snapshot> current = ThreadLocal.withInitial(() -> null);

  /**
   * Adds a fetched list and whether its source census was complete.
   *
   * @param rows decoded source rows
   * @param valid rows with a usable source identity
   * @param complete whether this list enumerates its requested source scope
   */
  public void fetched(long rows, long valid, boolean complete) {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      snapshot.fetched += rows;
      snapshot.valid += valid;
      snapshot.censusComplete &= complete;
      snapshot.fetchObserved = true;
    }
  }

  /**
   * Adds committed row writes.
   *
   * @param count rows whose write transaction committed
   */
  public void applied(long count) {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      snapshot.applied += count;
      snapshot.writeObserved = true;
    }
  }

  /**
   * Adds source rows deliberately excluded by validation or matching.
   *
   * @param count skipped source rows
   */
  public void skipped(long count) {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      snapshot.skipped += count;
    }
  }

  /**
   * Adds fetch or write failures that the caller handled without aborting the step.
   *
   * @param count failed operations
   */
  public void failed(long count) {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      snapshot.failed += count;
    }
  }

  /** Marks the source census unusable after an identity or validation conflict. */
  public void invalidateCensus() {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      snapshot.censusComplete = false;
      snapshot.censusRejected = true;
    }
  }

  /** Accepts a complete union census without overriding identity-validation rejection. */
  public void vouchForCensus() {
    Snapshot snapshot = current.get();
    if (snapshot != null && !snapshot.censusRejected) {
      snapshot.censusComplete = true;
      snapshot.fetchObserved = true;
    }
  }

  /**
   * Records the absence-based cleanup decision.
   *
   * @param performed whether the cleanup ran on a verified census
   */
  public void sweep(boolean performed) {
    Snapshot snapshot = current.get();
    if (snapshot != null) {
      if (!performed || snapshot.sweepOutcome != SyncSweepOutcome.SKIPPED) {
        snapshot.sweepOutcome = performed ? SyncSweepOutcome.PERFORMED : SyncSweepOutcome.SKIPPED;
      }
    }
  }

  @NotNull
  Snapshot begin(UUID runId) {
    Snapshot snapshot = new Snapshot(runId, current.get());
    current.set(snapshot);
    return snapshot;
  }

  void end() {
    Snapshot snapshot = current.get();
    if (snapshot == null || snapshot.parent == null) {
      current.remove();
      return;
    }
    Snapshot parent = snapshot.parent;
    parent.fetched += snapshot.fetched;
    parent.valid += snapshot.valid;
    parent.applied += snapshot.applied;
    parent.writeObserved |= snapshot.writeObserved;
    parent.skipped += snapshot.skipped;
    parent.failed += snapshot.failed;
    parent.fetchObserved |= snapshot.fetchObserved;
    parent.censusComplete &= snapshot.censusComplete;
    parent.censusRejected |= snapshot.censusRejected;
    if (snapshot.sweepOutcome == SyncSweepOutcome.SKIPPED
        || parent.sweepOutcome == SyncSweepOutcome.NOT_REQUIRED) {
      parent.sweepOutcome = snapshot.sweepOutcome;
    }
    current.set(parent);
  }

  @Nullable
  Snapshot active() {
    return current.get();
  }

  /** Counters confined to the thread applying one independently recorded catalogue slice. */
  @Getter
  static final class Snapshot {
    private final UUID runId;
    private final @Nullable Snapshot parent;
    private long fetched;
    private long valid;
    private long applied;
    private long skipped;
    private long failed;
    private boolean censusComplete = true;
    private boolean censusRejected;
    private boolean fetchObserved;
    private boolean writeObserved;
    private SyncSweepOutcome sweepOutcome = SyncSweepOutcome.NOT_REQUIRED;

    Snapshot(UUID runId, @Nullable Snapshot parent) {
      this.runId = runId;
      this.parent = parent;
    }

    SyncRunStatus status() {
      if (failed > 0 || !censusComplete || sweepOutcome == SyncSweepOutcome.SKIPPED) {
        return SyncRunStatus.PARTIAL;
      }
      return applied == 0 && writeObserved ? SyncRunStatus.UNCHANGED : SyncRunStatus.SUCCESS;
    }
  }
}
