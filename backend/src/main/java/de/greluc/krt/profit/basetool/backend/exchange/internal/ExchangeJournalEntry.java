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
 * One entry a client's exchange write changed, with its state before and after, kept 90 days so the
 * member can undo it (REQ-XCH-022, ADR-0218).
 */
@Entity
@Table(name = "exchange_journal")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeJournalEntry {

  /** The entry's id. */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The member whose entry was written. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The registry client that wrote it. */
  @Column(name = "client_id", nullable = false, updatable = false, length = 64)
  private String clientId;

  /** The writing installation's DPoP key thumbprint. */
  @Column(name = "installation_key", nullable = false, updatable = false, length = 64)
  private String installationKey;

  /** The change set the write belonged to. */
  @Column(name = "batch_id", nullable = false, updatable = false)
  private UUID batchId;

  /** The resource of the entry. */
  @Enumerated(EnumType.STRING)
  @Column(name = "resource", nullable = false, updatable = false, length = 16)
  private ExchangeResource resource;

  /** The entry's key, as the change feed records it. */
  @Column(name = "entity_key", nullable = false, updatable = false, length = 255)
  private String entityKey;

  /** What the write did. */
  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, updatable = false, length = 24)
  private ExchangeJournalAction action;

  /** Whether the write counts as a removal for the mass-change guard. */
  @Column(name = "removal", nullable = false, updatable = false)
  private boolean removal;

  /** The entry's state before the write as JSON, or {@code null} when it did not exist. */
  @Column(name = "before_state", updatable = false, columnDefinition = "TEXT")
  private String beforeState;

  /** The entry's state after the write as JSON, or {@code null} when it was removed. */
  @Column(name = "after_state", updatable = false, columnDefinition = "TEXT")
  private String afterState;

  /** The writing transaction's id, which places the write in the change feed. */
  @Column(name = "tx", nullable = false, updatable = false, insertable = false)
  private long tx;

  /** When the write happened. */
  @Column(name = "recorded_at", nullable = false, updatable = false, insertable = false)
  private Instant recordedAt;

  /** When the member undid the write, or {@code null}. */
  @Column(name = "undone_at")
  private Instant undoneAt;
}
