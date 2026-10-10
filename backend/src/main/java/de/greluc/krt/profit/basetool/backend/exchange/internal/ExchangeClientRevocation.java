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
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * When a member last disconnected a whole exchange client; a token issued before it is refused
 * (REQ-XCH-008). Written by an upsert, so it carries no version.
 */
@Entity
@Table(name = "exchange_client_revocation")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeClientRevocation {

  /** The client and the member. */
  @EmbeddedId private Key key;

  /** When the member disconnected the client. */
  @Column(name = "revoked_at", nullable = false)
  private Instant revokedAt;

  /**
   * The composite key.
   *
   * @param exchangeClientId the registry client
   * @param userId the member
   */
  @Embeddable
  public record Key(
      @Column(name = "exchange_client_id", nullable = false) UUID exchangeClientId,
      @Column(name = "user_id", nullable = false) UUID userId)
      implements Serializable {}
}
