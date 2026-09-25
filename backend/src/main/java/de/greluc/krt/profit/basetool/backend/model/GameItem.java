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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Joint UEX + SC Wiki item entity, keyed by the shared in-game asset {@link #externalUuid}.
 *
 * <p>Created by R2's {@code UexItemSyncService} (writes the UEX-side columns, sets {@link
 * #sourceSystems} to {@link GameItemSourceSystem#UEX_ONLY}); R4's {@code ScWikiItemSyncService}
 * (Wiki side) joins the same row by {@code external_uuid} and fills the Wiki-sourced columns
 * ({@link #scwikiSlug}, {@link #classification}, {@link #mass}, {@link #dimensionX}/Y/Z, {@link
 * #descriptionEn}/De), flipping {@code sourceSystems} to {@link GameItemSourceSystem#BOTH}.
 *
 * <p>{@link #kind} is the joint discriminator with the §6.3.1 tie-breaker rule ({@code
 * WEAPON_ATTACHMENT > WEAPON > VEHICLE_WEAPON > VEHICLE_ITEM > GENERIC}). {@link #manufacturer} is
 * sticky on the UEX value when UEX and Wiki disagree (§6.3.3) — UEX is the trading-data source and
 * its manufacturer link drives the inventory / refinery flows.
 *
 * <p>R2 ships with the Wiki-sourced columns NULL on every row; the R4 PR populates them in place.
 */
@Entity
@Table(name = "game_item")
@Getter
@Setter
@ToString(exclude = {"manufacturer", "uexCategory", "linkedShipType"})
@NoArgsConstructor
@AllArgsConstructor
public class GameItem extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /**
   * In-game RSI asset UUID. Identical between UEX {@code /items[].uuid} and SC Wiki {@code
   * /api/items/{uuid}} when both systems carry one. The cross-source join key — UNIQUE across the
   * table so a UEX row and a Wiki row for the same asset never coexist on two rows.
   *
   * <p>NULLABLE in R2: ~30% of UEX items ship with an empty uuid; those rows stay with {@code
   * external_uuid = NULL} until R3's Wiki slug-fallback resolves the missing UUIDs. Resolution on a
   * re-sync uses {@link #uexItemId} as the primary fast-path so the NULL gap does not break
   * idempotency.
   */
  @Column(name = "external_uuid", unique = true)
  private UUID externalUuid;

  /** Canonical display name. UEX writes it; the Wiki sync may overwrite per §6.3.3. */
  @Column(nullable = false)
  private String name;

  /** Joint manufacturer link. Sticky on UEX in case of a UEX/Wiki disagreement. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "manufacturer_id")
  private Manufacturer manufacturer;

  /** Joint kind discriminator (more-specific wins; see {@link GameItemKind}). */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private GameItemKind kind = GameItemKind.GENERIC;

  /** Which external catalogues have written to this row; defaults to UEX_ONLY for R2 inserts. */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_systems", nullable = false, length = 16)
  private GameItemSourceSystem sourceSystems = GameItemSourceSystem.UEX_ONLY;

  /** SC Wiki URL slug (e.g. {@code "venture-helmet-white-2"}). R4. */
  @Column(name = "scwiki_slug")
  private String scwikiSlug;

  /** RSI engine class name (e.g. {@code "rsi_explorer_armor_light_helmet_01_01_10"}). R4. */
  @Column(name = "class_name")
  private String className;

  /** Wiki classification (e.g. {@code "FPS.Armor.Helmet"}, {@code "Ship.Weapon.Gun"}). R4. */
  @Column(name = "classification")
  private String classification;

  /** Localised label for {@link #classification}. R4. */
  @Column(name = "classification_label")
  private String classificationLabel;

  /** Wiki type field (free-form taxonomy element). R4. */
  @Column(name = "wiki_type")
  private String wikiType;

  /** Wiki type display label. R4. */
  @Column(name = "wiki_type_label")
  private String wikiTypeLabel;

  /** Wiki sub-type. R4. */
  @Column(name = "wiki_sub_type")
  private String wikiSubType;

  /** Wiki sub-type display label. R4. */
  @Column(name = "wiki_sub_type_label")
  private String wikiSubTypeLabel;

  /** Component / weapon size tier. R4. */
  @Column(name = "size_class")
  private Integer sizeClass;

  /** Item grade marker (often a single character or short tier label). R4. */
  @Column(name = "grade")
  private String grade;

  /** Item rarity tier. R4. */
  @Column(name = "rarity")
  private String rarity;

  /** Mass in kg. R4. */
  @Column(name = "mass")
  private Double mass;

  /** Bounding box X (m). R4. */
  @Column(name = "dimension_x")
  private Double dimensionX;

  /** Bounding box Y (m). R4. */
  @Column(name = "dimension_y")
  private Double dimensionY;

  /** Bounding box Z (m). R4. */
  @Column(name = "dimension_z")
  private Double dimensionZ;

  /** English description from Wiki. R4. */
  @Column(name = "description_en", columnDefinition = "TEXT")
  private String descriptionEn;

  /** German description from Wiki. R4. */
  @Column(name = "description_de", columnDefinition = "TEXT")
  private String descriptionDe;

  /** Wiki flag indicating this is the base variant of a family. R4. */
  @Column(name = "is_base_variant")
  private Boolean isBaseVariant;

  /** Wiki flag indicating this item can be crafted via a blueprint. R4. */
  @Column(name = "is_craftable")
  private Boolean isCraftable;

  /** Last successful SC Wiki sync touch. R4. */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /** SC Wiki soft-delete marker. R4. */
  @Column(name = "scwiki_deleted_at")
  private Instant scwikiDeletedAt;

  /** Game version in which the Wiki sync last observed this row. R4. */
  @Column(name = "scwiki_game_version_seen")
  private String scwikiGameVersionSeen;

  /** UEX integer item id. Unique across the table; the fastest re-resolution key. */
  @Column(name = "uex_item_id", unique = true)
  private Integer uexItemId;

  /** UEX kebab-case slug (consumed by R3 slug-fallback resolution). */
  @Column(name = "uex_slug")
  private String uexSlug;

  /** Reference to the UEX category this item belongs to. Drives the kind derivation. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "uex_category_id")
  private UexCategory uexCategory;

  /** Raw UEX company id (denormalised — {@link #manufacturer} is canonical). */
  @Column(name = "uex_company_id")
  private Integer uexCompanyId;

  /** Raw UEX vehicle id for vehicle-bound items (paints, components). */
  @Column(name = "uex_vehicle_id")
  private Integer uexVehicleId;

  /**
   * Resolved {@link ShipType} FK for vehicle-bound items (e.g. "100i Auspicious Red Dog Livery"
   * links to the 100i row). Set from {@link #uexVehicleId} via the {@code ShipTypeRepository}
   * lookup in the sync.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "linked_ship_type_id")
  private ShipType linkedShipType;

  /** Primary colour of the variant. */
  @Column(name = "uex_color")
  private String uexColor;

  /** Secondary colour of the variant. */
  @Column(name = "uex_color2")
  private String uexColor2;

  /** Quality tier (0..n). */
  @Column(name = "uex_quality")
  private Integer uexQuality;

  /** RSI pledge store URL. */
  @Column(name = "uex_url_store")
  private String uexUrlStore;

  /** Screenshot URL. */
  @Column(name = "uex_screenshot")
  private String uexScreenshot;

  /** UEX flag (0/1) for pledge-store exclusivity, normalised to Boolean. */
  @Column(name = "is_exclusive_pledge")
  private Boolean isExclusivePledge;

  /** UEX flag for subscriber exclusivity. */
  @Column(name = "is_exclusive_subscriber")
  private Boolean isExclusiveSubscriber;

  /** UEX flag for concierge exclusivity. */
  @Column(name = "is_exclusive_concierge")
  private Boolean isExclusiveConcierge;

  /** UEX flag indicating the item also appears in the commodity catalogue. */
  @Column(name = "uex_is_commodity")
  private Boolean uexIsCommodity;

  /** UEX flag indicating the item is a harvestable. */
  @Column(name = "uex_is_harvestable")
  private Boolean uexIsHarvestable;

  /** Free-form UEX note (rarely populated; surfaced in admin sync report). */
  @Column(name = "uex_notification", columnDefinition = "TEXT")
  private String uexNotification;

  /** Last successful UEX sync touch. */
  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;

  /** UEX soft-delete marker. */
  @Column(name = "uex_deleted_at")
  private Instant uexDeletedAt;

  /** Game version in which UEX last observed this row. */
  @Column(name = "uex_game_version_seen")
  private String uexGameVersionSeen;

  /**
   * DataForge {@code __ref} asset GUID observed by the KRT P4K Reader import for this item. Kept
   * alongside (not in place of) {@link #externalUuid}: the importer backfills {@code external_uuid}
   * only when it is null and no other row holds the GUID, but always records the P4K-observed GUID
   * here so a UUID disagreement between UEX/Wiki and the game DCB stays auditable. Not UNIQUE —
   * conflicting rows may legitimately carry the same P4K GUID until reconciled.
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
