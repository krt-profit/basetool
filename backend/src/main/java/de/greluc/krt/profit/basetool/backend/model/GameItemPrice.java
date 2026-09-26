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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * UEX retail price of one {@link GameItem} at one {@link Terminal}; the pair is unique and upserted
 * by {@code UexItemPriceSyncService}.
 *
 * <p>Pairs UEX no longer returns keep their row with nulled prices. {@code priceRent}, {@code
 * statusBuy}, {@code statusSell} and {@code gameVersion} are not supplied by the feed and stay
 * {@code null}.
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"game_item_id", "terminal_id"}))
public class GameItemPrice extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "game_item_id", nullable = false)
  @ToString.Exclude
  private GameItem gameItem;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "terminal_id", nullable = false)
  @ToString.Exclude
  private Terminal terminal;

  @Column(name = "price_buy")
  private Double priceBuy;

  @Column(name = "price_sell")
  private Double priceSell;

  @Column(name = "price_rent")
  private Double priceRent;

  @Column(name = "status_buy")
  private Integer statusBuy;

  @Column(name = "status_sell")
  private Integer statusSell;

  @Column(name = "date_modified")
  private Long dateModified;

  @Column(name = "game_version")
  private String gameVersion;

  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;
}
