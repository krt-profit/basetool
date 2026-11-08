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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexCompany;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.GameText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.Nullable;

/**
 * Manufacturer JPA entity. Class-level {@code @BatchSize} batches the initialisation of lazy
 * manufacturer proxies when Lager item views are mapped (REQ-INV-029).
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@BatchSize(size = 50)
public class Manufacturer extends AbstractEntity<UUID> implements P4kKeyedRow {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String name;

  /**
   * The German name, written by the P4K import only (REQ-P4K-023); pages in German show it, and
   * search and matching run on {@link #name}.
   */
  @Column(name = "name_de")
  @Nullable
  private String nameDe;

  /**
   * Short display code (e.g. {@code "AEGS"}). Not unique: several UEX company records of one brand
   * reduce to the same code and are merged onto one row (ADR-0023). Identity lives on {@link
   * #uexCompanyId} / {@link #scwikiUuid} / {@link #name}.
   */
  @Column(nullable = false)
  private String abbreviation;

  @Column private String nickname;

  @Column private String wiki;

  @Column(columnDefinition = "TEXT")
  @Nullable
  @GameText
  private String description;

  /** The German description, written by the P4K import only (REQ-P4K-020). */
  @Column(name = "description_de", columnDefinition = "TEXT")
  @Nullable
  @GameText
  private String descriptionDe;

  private boolean hidden = false;

  /**
   * The canonical (lowest) UEX company id of this brand; unique. All of the brand's UEX company ids
   * map to this row through {@link ManufacturerUexCompany}, which the syncs resolve against
   * (ADR-0023).
   */
  @Column(name = "uex_company_id", unique = true)
  private Integer uexCompanyId;

  /**
   * SC Wiki manufacturer UUID. Populated by the R6 manufacturer reconciliation. UNIQUE so the same
   * Wiki entity cannot be linked to two local manufacturers.
   */
  @Column(name = "scwiki_uuid", unique = true)
  private UUID scwikiUuid;

  /** SC Wiki manufacturer short code (e.g. {@code "RSI"}, {@code "AEGS"}). */
  @Column(name = "scwiki_code")
  private String scwikiCode;

  /** Industry / sector label exposed by UEX (e.g. {@code "Aerospace"}, {@code "Fashion"}). */
  @Column(name = "industry")
  private String industry;

  /** Whether UEX flags this company as an item manufacturer; {@code null} until the first sync. */
  @Column(name = "is_item_manufacturer")
  private Boolean isItemManufacturer;

  /**
   * Whether UEX flags this company as a vehicle manufacturer; {@code null} until the first sync.
   */
  @Column(name = "is_vehicle_manufacturer")
  private Boolean isVehicleManufacturer;

  /** Timestamp of the most recent successful UEX sync touch. */
  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;

  /** Timestamp of the most recent successful SC Wiki sync touch (R6+). */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /**
   * Timestamp of the first sync run in which UEX no longer returned this manufacturer. Soft-delete
   * marker; cleared on the next sync that sees it again.
   */
  @Column(name = "uex_deleted_at")
  @Nullable
  private Instant uexDeletedAt;

  /** Soft-delete marker mirroring {@link #uexDeletedAt} for the SC Wiki side. */
  @Column(name = "scwiki_deleted_at")
  @Nullable
  private Instant scwikiDeletedAt;

  /**
   * The P4K GUID, the row's identity in the catalogue and unique per kind (REQ-P4K-007); the SC
   * Wiki UUID stays in {@code scwiki_uuid}.
   */
  @Column(name = "p4k_uuid", unique = true)
  private UUID p4kUuid;

  /** Last successful KRT P4K Reader import touch; non-null marks P4K participation. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /**
   * When the P4K sweep found the row gone from a complete catalogue (REQ-P4K-011), or {@code null};
   * a later match clears it.
   */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /**
   * When an admin hid the row through a P4K review case (REQ-P4K-016), or {@code null}; no match
   * clears it.
   */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /** The P4K import run that last matched this row (REQ-P4K-005), or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;
}
