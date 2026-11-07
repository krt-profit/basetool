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

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.kernel.GameText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.Nullable;

/** Material JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Material extends AbstractEntity<UUID> implements P4kKeyedRow {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  @Column(name = "id_commodity", unique = true)
  private Integer idCommodity;

  @Column(nullable = false, unique = true)
  private String name;

  /**
   * The German name, written by the P4K import only (REQ-P4K-023); pages in German show it, and
   * search and matching run on {@link #name}.
   */
  @Column(name = "name_de")
  @Nullable
  private String nameDe;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MaterialType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "quantity_type", nullable = false)
  private QuantityType quantityType = QuantityType.SCU;

  @Column(columnDefinition = "TEXT")
  @Nullable
  @GameText
  private String description;

  @Column(name = "code")
  private String code;

  @Column(name = "slug")
  private String slug;

  @Column(name = "kind")
  private String kind;

  @Column(name = "weight_scu")
  private Double weightScu;

  @Column(name = "price_buy")
  private Double priceBuy;

  @Column(name = "price_sell")
  private Double priceSell;

  @Column(name = "is_available")
  private Integer isAvailable;

  @Column(name = "is_available_live")
  private Integer isAvailableLive;

  @Column(name = "is_extractable")
  private Integer isExtractable;

  @Column(name = "is_mineral")
  private Integer isMineral;

  @Column(name = "is_raw")
  private Integer isRaw;

  @Column(name = "is_pure")
  private Integer isPure;

  @Column(name = "is_refined")
  private Integer isRefined;

  @Column(name = "is_refinable")
  private Integer isRefinable;

  @Column(name = "is_harvestable")
  private Integer isHarvestable;

  @Column(name = "is_buyable")
  private Integer isBuyable;

  @Column(name = "is_sellable")
  private Integer isSellable;

  @Column(name = "is_temporary")
  private Integer isTemporary;

  @Column(name = "is_illegal")
  private Integer isIllegal;

  @Column(name = "is_volatile_qt")
  private Integer isVolatileQt;

  @Column(name = "is_volatile_time")
  private Integer isVolatileTime;

  @Column(name = "is_inert")
  private Integer isInert;

  @Column(name = "is_explosive")
  private Integer isExplosive;

  @Column(name = "is_buggy")
  private Integer isBuggy;

  @Column(name = "is_fuel")
  private Integer isFuel;

  @Column(name = "is_manual_raw_material", nullable = false)
  private Boolean isManualRawMaterial = false;

  @Column(name = "is_job_order", nullable = false)
  private Boolean isJobOrder = false;

  /**
   * SC Wiki commodity UUID. Populated by the R3 Wiki commodity sync when a UEX commodity is matched
   * against a Wiki entry (or a stand-alone Wiki row is inserted). {@code null} on every R1 row;
   * UNIQUE at the DB level so a Wiki UUID can be paired with at most one local material.
   */
  @Column(name = "scwiki_uuid", unique = true)
  private UUID scwikiUuid;

  /** SC Wiki internal key (e.g. {@code "Agricium"}). Used as a debug aid and audit log field. */
  @Column(name = "scwiki_key")
  private String scwikiKey;

  /** SC Wiki URL slug (e.g. {@code "agricium"}). Used in admin UI deep-links to the Wiki page. */
  @Column(name = "scwiki_slug")
  private String scwikiSlug;

  /**
   * Timestamp of the most recent successful SC Wiki sync touch on this row; {@code null} until the
   * Wiki commodity sync first writes it.
   */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /**
   * Timestamp of the first sync run in which the SC Wiki catalogue no longer returned this row. Set
   * instead of {@code DELETE} so other FKs (orders, inventory) remain valid; cleared on the next
   * sync that sees the row again.
   */
  @Column(name = "scwiki_deleted_at")
  @Nullable
  private Instant scwikiDeletedAt;

  /** Physical density in g/cc. Sourced from SC Wiki only — UEX does not expose density. */
  @Column(name = "density_g_per_cc")
  private Double densityGramPerCc;

  /** Refining instability factor (Wiki-only refining hint, not yet consumed by the UI). */
  @Column(name = "instability")
  private Double instability;

  /** Refining resistance factor (Wiki-only refining hint, not yet consumed by the UI). */
  @Column(name = "resistance")
  private Double resistance;

  /**
   * Catalog-visibility flag. The Wiki commodity sync inserts Wiki-only rows as {@code false} so
   * they stay out of trading flows until an admin reviews them.
   */
  @Column(name = "is_visible", nullable = false)
  private Boolean isVisible = true;

  /**
   * Which external catalogues have seen this row. Defaults to {@link MaterialSourceSystem#UEX_ONLY}
   * to match the post-V106 state of every existing material row. The R3 Wiki commodity sync flips
   * {@code UEX_ONLY → BOTH} (or inserts new rows as {@link MaterialSourceSystem#WIKI_ONLY}); the R8
   * backfill maps {@code is_manual_entry=true → MANUAL}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_systems", nullable = false, length = 16)
  private MaterialSourceSystem sourceSystems = MaterialSourceSystem.UEX_ONLY;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refined_material_id")
  @ToString.Exclude
  @Nullable
  private Material refinedMaterial;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  @ToString.Exclude
  @Nullable
  private MaterialCategory category;

  /**
   * The P4K GUID, the row's identity in the catalogue and unique per kind (REQ-P4K-007); the SC
   * Wiki UUID stays in {@code scwiki_uuid}.
   */
  @Column(name = "p4k_uuid", unique = true)
  private UUID p4kUuid;

  /**
   * The {@code global.ini} name key without the leading {@code @}, from the P4K import; resolves an
   * exchange {@code locKey} (REQ-XCH-012).
   */
  @Column(name = "name_key", length = 200)
  @Nullable
  private String nameKey;

  /** Wiki-owned mining and cargo enrichment, independent of UEX market data. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "wiki_mining_data", columnDefinition = "jsonb")
  @Nullable
  private CommodityMiningData miningData;

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
