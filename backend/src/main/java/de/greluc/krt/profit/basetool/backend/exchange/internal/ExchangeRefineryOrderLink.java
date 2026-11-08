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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The client key the creating client gave a refinery order (REQ-XCH-052): unique per member and
 * client, and at most one per order. The order id carries no foreign key, so the key outlives the
 * order for its tombstone and for the undo of a delete.
 */
@Entity
@Table(name = "exchange_refinery_order_link")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRefineryOrderLink {

  /** The link's id. */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The member who owned the order when the client created it. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The registry client that created the order. */
  @Column(name = "client_id", nullable = false, updatable = false, length = 64)
  private String clientId;

  /** The client's own key of the order. */
  @Column(name = "client_key", nullable = false, updatable = false, length = 128)
  private String clientKey;

  /** The order. */
  @Column(name = "refinery_order_id", nullable = false, updatable = false)
  private UUID refineryOrderId;

  /** When the client created the order. */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
