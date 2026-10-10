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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One entry of the exchange change feed: a member's entity of one resource changed (REQ-XCH-013,
 * ADR-0224).
 *
 * <p>Written only by database triggers, so every write path is sequenced; the application reads and
 * purges it.
 */
@Entity
@Immutable
@Table(name = "exchange_change")
@Getter
@NoArgsConstructor
public class ExchangeChange {

  /** The database sequence number, the minor part of the feed position. */
  @Id
  @Column(name = "seq", nullable = false, updatable = false)
  private Long seq;

  /** The id of the writing transaction, the major part of the feed position. */
  @Column(name = "tx", nullable = false, updatable = false, insertable = false)
  private long tx;

  /** The member whose entity changed. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** The resource the entity belongs to. */
  @Enumerated(EnumType.STRING)
  @Column(name = "resource", nullable = false, updatable = false, length = 16)
  private ExchangeResource resource;

  /** The entity's key within the resource, such as a blueprint's product key. */
  @Column(name = "entity_key", nullable = false, updatable = false, length = 255)
  private String entityKey;

  /** Who changed it: {@code web}, {@code app}, {@code client} or {@code system}. */
  @Column(name = "source_channel", nullable = false, updatable = false, length = 8)
  private String sourceChannel;

  /** The registry client id when a client changed it, otherwise {@code null}. */
  @Column(name = "source_client", updatable = false, length = 64)
  private String sourceClient;

  /** The installation's key thumbprint when a client changed it, otherwise {@code null}. */
  @Column(name = "source_key", updatable = false, length = 64)
  private String sourceKey;

  /** When it changed. */
  @Column(name = "changed_at", nullable = false, updatable = false)
  private Instant changedAt;
}
