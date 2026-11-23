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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A dirty mark: a job order whose exchange fingerprint the worker must recompute (REQ-OXCH-003,
 * ADR-0406). Triggers on the order's source tables insert the marks, one per marking transaction;
 * the worker only reads and deletes them.
 */
@Entity
@Table(name = "exchange_job_order_dirty")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeJobOrderMark {

  /** The mark's id. */
  @Id
  @Column(name = "id", nullable = false, insertable = false, updatable = false)
  private Long id;

  /** The marked order. */
  @Column(name = "job_order_id", nullable = false, insertable = false, updatable = false)
  private UUID jobOrderId;

  /** The id of the transaction that set the mark. */
  @Column(name = "tx", nullable = false, insertable = false, updatable = false)
  private long tx;

  /** When the mark was set. */
  @Column(name = "marked_at", nullable = false, insertable = false, updatable = false)
  private Instant markedAt;
}
