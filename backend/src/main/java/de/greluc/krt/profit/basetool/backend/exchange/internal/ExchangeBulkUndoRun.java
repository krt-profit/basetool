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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An admin's undo of one exchange client's writes for every member since a point in time, and its
 * progress (REQ-XCH-034, ADR-0227).
 *
 * <p>Only the run's worker changes a running row, through atomic counter updates, so it carries no
 * optimistic-lock version.
 */
@Entity
@Table(name = "exchange_bulk_undo_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeBulkUndoRun {

  /** The run's id. */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The registry id of the client whose writes are undone. */
  @Column(name = "exchange_client_id", nullable = false, updatable = false)
  private UUID exchangeClientId;

  /** The client id whose writes are undone. */
  @Column(name = "client_id", nullable = false, updatable = false, length = 64)
  private String clientId;

  /** The one installation the run is limited to, or {@code null} for all. */
  @Column(name = "installation_id", updatable = false)
  private UUID installationId;

  /** The key thumbprint of that installation, or {@code null} for all. */
  @Column(name = "installation_key", updatable = false, length = 64)
  private String installationKey;

  /** The one resource the run is limited to, or {@code null} for all. */
  @Enumerated(EnumType.STRING)
  @Column(name = "resource", updatable = false, length = 16)
  private ExchangeResource resource;

  /** The start of the undone span, already clamped to the retention. */
  @Column(name = "since", nullable = false, updatable = false)
  private Instant since;

  /** The admin who started the run, or {@code null} once that account is gone. */
  @Column(name = "requested_by", updatable = false)
  private UUID requestedBy;

  /** The run's state. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ExchangeBulkUndoStatus status;

  /** How many members had undoable writes in scope when the run started. */
  @Column(name = "members_total", nullable = false, updatable = false)
  private int membersTotal;

  /** How many members were processed, failed ones included. */
  @Column(name = "members_done", nullable = false)
  private int membersDone;

  /** How many members could not be processed. */
  @Column(name = "members_failed", nullable = false)
  private int membersFailed;

  /** How many entries were restored. */
  @Column(name = "restored", nullable = false)
  private int restored;

  /** How many entries were left alone. */
  @Column(name = "skipped", nullable = false)
  private int skipped;

  /** When the run started. */
  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  /** When the run ended, or {@code null} while it runs. */
  @Column(name = "finished_at")
  private Instant finishedAt;
}
