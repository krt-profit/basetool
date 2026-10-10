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
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An entry a bulk undo left alone, or a member it could not process, kept by reference so no entry
 * name or other member text is copied (REQ-XCH-034).
 */
@Entity
@Table(name = "exchange_bulk_undo_skip")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeBulkUndoSkip {

  /** The row's id. */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The run. */
  @Column(name = "run_id", nullable = false, updatable = false)
  private UUID runId;

  /** The member whose entry it is. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The newest journal entry of the skipped entry, or {@code null} for a failed member. */
  @Column(name = "journal_entry_id", updatable = false)
  private UUID journalEntryId;

  /** The entry's resource, or {@code null} for a failed member. */
  @Enumerated(EnumType.STRING)
  @Column(name = "resource", updatable = false, length = 16)
  private ExchangeResource resource;

  /** {@code CHANGED_AFTERWARDS}, {@code GONE}, or {@code FAILED} for a member not processed. */
  @Column(name = "reason", nullable = false, updatable = false, length = 24)
  private String reason;
}
