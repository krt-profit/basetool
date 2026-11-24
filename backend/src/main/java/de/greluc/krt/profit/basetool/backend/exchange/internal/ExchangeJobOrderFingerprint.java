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
import java.util.Arrays;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The stored exchange fingerprint of one job order (REQ-OXCH-003, ADR-0406); written by the
 * fingerprint worker through {@link ExchangeJobOrderFingerprintRepository#write} and by the window
 * run through {@link ExchangeJobOrderFingerprintRepository#markLeftSet} only.
 */
@Entity
@Table(name = "exchange_job_order_fingerprint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeJobOrderFingerprint {

  /** The order. */
  @Id
  @Column(name = "job_order_id", nullable = false, updatable = false)
  private UUID jobOrderId;

  /** The fingerprint; kept private, compared through {@link #holds(byte[])}. */
  @Getter(AccessLevel.NONE)
  @Column(name = "fingerprint", nullable = false)
  private byte[] fingerprint;

  /** When the worker last found the fingerprint changed: the read's {@code updatedAt}. */
  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  /** The read's {@code settlementOpen} as of the fingerprint. */
  @Column(name = "settlement_open", nullable = false)
  private boolean settlementOpen;

  /**
   * When the order was announced as outside the client set, by a fingerprint written while it lay
   * outside or by the window run; {@code null} while its last fingerprint was written inside the
   * set.
   */
  @Column(name = "left_set_at")
  @Nullable
  private Instant leftSetAt;

  /**
   * Tells whether the stored fingerprint equals a freshly computed one.
   *
   * @param candidate the computed fingerprint
   * @return {@code true} when both hold the same bytes
   */
  boolean holds(byte @NotNull [] candidate) {
    return Arrays.equals(fingerprint, candidate);
  }
}
