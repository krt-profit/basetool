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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/** Durable lifecycle and application counters of one independently applied catalogue slice. */
@Entity
@Table(name = "external_sync_run")
@Getter
@Setter
@NoArgsConstructor
public class ExternalSyncRun {
  /** Stable identity shared by the attempt's anomaly events. */
  @Id private UUID id;

  /** Optimistic-lock counter for lifecycle transitions. */
  @Version private Long version;

  /** External system whose catalogue is being applied. */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_system", nullable = false, length = 16)
  private SyncSourceSystem sourceSystem;

  /** Independently applied slice or its enclosing source sweep. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 64)
  private SyncAggregate aggregate;

  /** Persisted application outcome, including unfinished attempts. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private SyncRunStatus status;

  /** Actual start of this attempt. */
  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  /** Actual finish, absent while running or after an interrupted process. */
  @Nullable
  @Column(name = "finished_at")
  private Instant finishedAt;

  /** Frozen source build, absent for an unversioned feed. */
  @Nullable
  @Column(name = "game_version", length = 128)
  private String gameVersion;

  /** Decoded source rows across list and detail requests. */
  @Column(nullable = false)
  private long fetched;

  /** Decoded rows with usable source identities. */
  @Column(nullable = false)
  private long valid;

  /** Row writes whose transaction committed. */
  @Column(nullable = false)
  private long applied;

  /** Rows deliberately rejected by validation or matching. */
  @Column(nullable = false)
  private long skipped;

  /** Fetch or write failures handled within this attempt. */
  @Column(nullable = false)
  private long failed;

  /** Whether the applied source scope had a verified complete census. */
  @Column(name = "census_complete", nullable = false)
  private boolean censusComplete;

  /** Whether absence-based cleanup ran or was refused. */
  @Enumerated(EnumType.STRING)
  @Column(name = "sweep_outcome", nullable = false, length = 16)
  private SyncSweepOutcome sweepOutcome;
}
