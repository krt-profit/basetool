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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The single row that records how far the change feed has been purged: a cursor below it has lost
 * entries and answers {@code 410 CURSOR_EXPIRED} (REQ-XCH-013).
 */
@Entity
@Table(name = "exchange_feed_horizon")
@Getter
@Setter
@NoArgsConstructor
public class ExchangeFeedHorizon {

  /** The id of the single row. */
  public static final short SINGLETON_ID = 1;

  /** Always {@link #SINGLETON_ID}. */
  @Id
  @Column(name = "id", nullable = false)
  private Short id;

  /** The transaction id of the last feed position the purge removed, {@code 0} before the first. */
  @Column(name = "purged_through_tx", nullable = false)
  private long purgedThroughTx;

  /**
   * The sequence number of the last feed position the purge removed, {@code 0} before the first.
   */
  @Column(name = "purged_through_seq", nullable = false)
  private long purgedThroughSeq;

  /** When the purge last removed entries, or {@code null} before the first. */
  @Column(name = "purged_at")
  private Instant purgedAt;
}
