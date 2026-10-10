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
import lombok.Setter;

/**
 * One installation's link of its own id for a ship to the member's ship on the server
 * (REQ-XCH-017). A server ship is linked at most once per installation, and an external id names at
 * most one ship.
 */
@Entity
@Table(name = "exchange_ship_link")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeShipLink {

  /** The link's id. */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The member who owns the ship. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The registry client of the installation. */
  @Column(name = "client_id", nullable = false, updatable = false, length = 64)
  private String clientId;

  /** The installation's DPoP key thumbprint. */
  @Column(name = "installation_key", nullable = false, updatable = false, length = 64)
  private String installationKey;

  /** The installation's id for the ship. */
  @Column(name = "external_id", nullable = false, updatable = false, length = 128)
  private String externalId;

  /** The server ship. */
  @Column(name = "ship_id", nullable = false, updatable = false)
  private UUID shipId;

  /** When the link was made. */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
