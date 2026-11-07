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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopKind;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * A shop of the game, imported from the P4K catalogue's {@code shops} by its unique key
 * (REQ-P4K-042, ADR-0347): one placement of a shop entity, with its place and its item list. The
 * P4K import writes every other column; the UEX terminal link is mapped read only and written by
 * {@link ShopRepository} alone, so a save of the shop never overwrites an admin's link. Its names
 * are names of containers and entities, no game text (REQ-P4K-033).
 */
@Entity
@Table(name = "shop")
@Getter
@Setter
@NoArgsConstructor
public class Shop extends AbstractEntity<UUID> implements P4kKeyedRow {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The catalogue's key, the comma-separated CryGUID chain down to the shop entity; unique. */
  @Column(name = "shop_key", nullable = false, unique = true, columnDefinition = "TEXT")
  private String shopKey;

  /** The name-based UUID of the key, which the shared P4K machinery keys the row by; unique. */
  @Column(name = "p4k_uuid", nullable = false, unique = true)
  private UUID p4kUuid;

  /** The CryGUID of the shop entity, shared by every placement of a shop module. */
  @Column(name = "entity_guid")
  @Nullable
  private UUID entityGuid;

  /** The name of the shop entity, e.g. {@code SCShop_CommEx_TDD_NewBabbage}. */
  @Column(name = "entity_name")
  @Nullable
  private String entityName;

  /** The registry's shop name, e.g. {@code L_TDD_NewBabbage}; many shops share one. */
  @Column(name = "registry_name")
  @Nullable
  private String registryName;

  /** The star system whose registry holds the shop, e.g. {@code stanton}. */
  @Column(name = "star_system", length = 64)
  @Nullable
  private String starSystem;

  /** The location container the catalogue's starmap link goes by. */
  @Column(name = "location_name")
  @Nullable
  private String locationName;

  /** The kind, or {@code null} when the catalogue states none or one the Basetool does not know. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", length = 32)
  @Nullable
  private P4kShopKind kind;

  /** The brand folder, e.g. {@code tdd}. */
  @Column(name = "brand", length = 64)
  @Nullable
  private String brand;

  /** Whether the shop is a commodity exchange or a commodity terminal. */
  @Column(name = "commodity_exchange", nullable = false)
  private boolean commodityExchange;

  /** The stem of the shop's inventory file; {@code null} while its item list is unknown. */
  @Column(name = "inventory")
  @Nullable
  private String inventory;

  /** The starmap object the shop sits at, or {@code null} when the catalogue names none. */
  @Column(name = "starmap_object_id")
  @Nullable
  private UUID starmapObjectId;

  /** The linked UEX terminal, read only: {@link ShopRepository} writes it. */
  @Column(name = "terminal_id", insertable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  @Nullable
  private UUID terminalId;

  /** Whether an admin set or cleared the terminal link, read only: {@link ShopRepository}. */
  @Column(name = "terminal_link_manual", insertable = false, updatable = false)
  @Setter(AccessLevel.NONE)
  private boolean terminalLinkManual;

  /** The time of the last P4K match. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /** The P4K import run that last matched the shop (REQ-P4K-005), or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;

  /**
   * When the sweep found the shop gone from a complete catalogue (REQ-P4K-011), or {@code null}.
   */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /** When an admin hid the shop through a review case (REQ-P4K-016), or {@code null}. */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /**
   * Whether the shop's item list is known.
   *
   * @return {@code true} when the catalogue named an inventory file for it
   */
  public boolean hasItemList() {
    return inventory != null;
  }
}
