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

import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * One external client on one PC, identified by its DPoP key thumbprint (REQ-XCH-007); once revoked,
 * the row is the deny-list entry for that key (REQ-XCH-008).
 */
@Entity
@Table(name = "exchange_installation")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ExchangeInstallation extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The registry client this installation runs. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "exchange_client_id", nullable = false, updatable = false)
  @ToString.Exclude
  private ExchangeClient client;

  /** The member the installation acts for. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, updatable = false)
  @ToString.Exclude
  private User user;

  /** The base64url SHA-256 thumbprint of the installation's DPoP key. */
  @Column(name = "key_thumbprint", nullable = false, updatable = false, length = 64)
  @ToString.Exclude
  private String keyThumbprint;

  /** The label the client gave the installation, or {@code null}; never logged. */
  @Nullable
  @Column(length = 40)
  @ToString.Exclude
  private String label;

  /** When the installation was first seen. */
  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  /** When the installation was last seen, updated at most every few minutes. */
  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  /** When the member disconnected the installation, or {@code null} while it is connected. */
  @Nullable
  @Column(name = "revoked_at")
  private Instant revokedAt;
}
