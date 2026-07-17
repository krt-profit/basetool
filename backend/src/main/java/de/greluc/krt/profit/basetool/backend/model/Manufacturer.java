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

/**
 * Manufacturer JPA entity. The class-level {@code @BatchSize} batches the initialisation of lazy
 * {@code Manufacturer} proxies (Hibernate 6 honours it on the <em>target</em> entity for to-one
 * associations): the Lager item views project {@code GameItem.manufacturer} into every stack /
 * catalog reference row (REQ-INV-029), so without batching each distinct manufacturer proxy would
 * fire its own {@code SELECT} while a grouped page is mapped.
 */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@BatchSize(size = 50)
public class Manufacturer extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String name;

  /**
   * Short display code (e.g. {@code "AEGS"}, {@code "Esperia"}). Deliberately <strong>not</strong>
   * UNIQUE (dropped in {@code V158}): the UEX {@code /companies} sync derives it from each
   * company's nickname, and UEX ships several distinct company records for the same brand that all
   * reduce to one code (observed: {@code 87 "Esperia"} + {@code 278 "Esperia Incorporation"} →
   * {@code "Esperia"}). The sync now <em>merges</em> those duplicates onto one row keyed by the
   * shared abbreviation (ADR-0023); the brand's several UEX company ids live in {@link
   * ManufacturerUexCompany}. Identity lives on {@link #uexCompanyId} / {@link #scwikiUuid} / {@link
   * #name}, not on this label.
   */
  @Column(nullable = false)
  private String abbreviation;

  @Column private String nickname;

  @Column private String wiki;

  @Column(columnDefinition = "TEXT")
  private String description;

  private boolean hidden = false;

  /**
   * The <em>canonical</em> UEX integer company id for this brand — the lowest id among the
   * duplicate company records UEX ships for it (the feed is processed ascending, so the
   * first/lowest claims the row). UNIQUE: it owns this row's display identity. The full set of UEX
   * company ids a brand owns (canonical + duplicates) is mapped to this row by {@link
   * ManufacturerUexCompany}; the item and vehicle syncs resolve through that alias table, not
   * through this single column (ADR-0023).
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

  /** Whether UEX flags this company as an item manufacturer. {@code null} until R2 sync runs. */
  @Column(name = "is_item_manufacturer")
  private Boolean isItemManufacturer;

  /** Whether UEX flags this company as a vehicle manufacturer. {@code null} until R2 sync runs. */
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
  private Instant uexDeletedAt;

  /** Soft-delete marker mirroring {@link #uexDeletedAt} for the SC Wiki side. */
  @Column(name = "scwiki_deleted_at")
  private Instant scwikiDeletedAt;

  // ───── KRT P4K Reader source lane (catalog import) ─────

  /**
   * DataForge {@code __ref} manufacturer GUID observed by the KRT P4K Reader import. Kept alongside
   * (not in place of) {@link #scwikiUuid}: the importer backfills {@code scwiki_uuid} only when it
   * is null and unclaimed, but always records the P4K-observed GUID here so a UUID disagreement
   * stays auditable. Not UNIQUE.
   */
  @Column(name = "p4k_uuid")
  private UUID p4kUuid;

  /** Last successful KRT P4K Reader import touch; non-null marks P4K participation. */
  @Column(name = "p4k_synced_at")
  private Instant p4kSyncedAt;

  /** KRT P4K Reader soft-delete marker (reserved for a future orphan sweep). */
  @Column(name = "p4k_deleted_at")
  private Instant p4kDeletedAt;
}
