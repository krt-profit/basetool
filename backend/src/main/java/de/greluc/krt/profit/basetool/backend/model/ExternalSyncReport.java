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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One append-only row recording a finding of an external sync run.
 *
 * <p>Not an {@link AbstractEntity}: rows are only inserted and later pruned, so there is no
 * {@code @Version}; {@link #ranAt} is the single timestamp, shared by all events of one {@link
 * #runId}. Written by {@code SyncReportService}, which keeps the last 30 runs per source.
 */
@Entity
@Table(name = "external_sync_report")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalSyncReport {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Groups every event emitted within one sync cycle so the admin UI can collapse them. */
  @Column(name = "run_id", nullable = false)
  private UUID runId;

  /** Wall-clock time the event was recorded; shared across a run's events. */
  @Column(name = "ran_at", nullable = false)
  private Instant ranAt;

  /** Which external catalogue produced the event. */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_system", nullable = false, length = 16)
  private SyncSourceSystem sourceSystem;

  /** The kind of finding (see {@link SyncEventType}). */
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 64)
  private SyncEventType eventType;

  /**
   * The aggregate the event concerns: {@code "commodity"}, {@code "blueprint"}, {@code "game_item"}
   * or {@code "ship_type"}. Kept a free-form string (not an enum) because the set is small and the
   * admin filter treats it as an opaque label.
   */
  @Column(name = "aggregate", nullable = false, length = 64)
  private String aggregate;

  /** The external asset UUID the event concerns, when applicable. */
  @Column(name = "external_uuid")
  private UUID externalUuid;

  /** The external integer id (UEX) the event concerns, when applicable. */
  @Column(name = "external_id")
  private Integer externalId;

  /** The external display name the event concerns, when applicable. */
  @Column(name = "external_name", length = 255)
  private String externalName;

  /** Free-form human-readable detail (the candidate names for a multi-match, etc.). */
  @Column(name = "detail", columnDefinition = "TEXT")
  private String detail;
}
