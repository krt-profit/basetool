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

/**
 * Ship / vehicle catalogue entry, jointly synced from UEX and (R4+) SC Wiki.
 *
 * <p>R2 expands the entity to mirror SC_WIKI_SYNC_PLAN.md §6.5: the hardened {@code
 * UexVehicleService} populates the 36 {@code is_*} capability flags, dimensions, fuel and urls from
 * the extended {@code UexVehicleDto}, plus the shared {@link #externalUuid} / {@link #uexVehicleId}
 * cross-source keys. It writes neither description column — UEX serves no description at all — so
 * {@link #descriptionEn} is filled by the SC-Wiki vehicle sync and the P4K import, never by UEX.
 * Wiki-side columns ({@link #scwikiSlug}, {@link #gameName}, {@link #descriptionDe}, …) stay
 * nullable until R4.
 *
 * <p>SC Wiki sync R9 Step 4 dropped the legacy synthesized {@code description} column — migration
 * {@code V125__drop_legacy_material_and_ship_type_columns.sql}, shipped 2026-06-01 — together with
 * the JPA field that mapped it, so no {@code description} member exists on this entity any more.
 * Ship-type descriptions come from {@link #descriptionEn} / {@link #descriptionDe}.
 */
@Entity
@Getter
@Setter
@ToString(exclude = "manufacturer")
@NoArgsConstructor
@AllArgsConstructor
public class ShipType extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String name;

  @ManyToOne
  @JoinColumn(name = "manufacturer_id")
  private Manufacturer manufacturer;

  private Integer scu;

  private boolean hidden = false;

  // ───── joint cross-source keys (R2 writes) ─────

  /** In-game RSI asset UUID. Shared with SC Wiki; the cross-source join key. */
  @Column(name = "external_uuid", unique = true)
  private UUID externalUuid;

  /** UEX integer vehicle id (1, 2, …). Fastest re-resolution key on subsequent UEX syncs. */
  @Column(name = "uex_vehicle_id", unique = true)
  private Integer uexVehicleId;

  /** UEX kebab-case slug (e.g. {@code "100i"}). */
  @Column(name = "uex_slug")
  private String uexSlug;

  /** SC Wiki kebab-case slug (e.g. {@code "orig-100i"}). R4 writes. */
  @Column(name = "scwiki_slug")
  private String scwikiSlug;

  // ───── canonical specs (last writer wins by field per §6.3.3) ─────

  /** Full marketing name (e.g. {@code "Origin 100i"}). */
  @Column(name = "name_full")
  private String nameFull;

  /** Wiki's game name field (R4). */
  @Column(name = "game_name")
  private String gameName;

  /** RSI engine class name (R4). */
  @Column(name = "class_name")
  private String className;

  /** Minimum crew complement. */
  @Column(name = "crew_min")
  private Integer crewMin;

  /** Maximum crew complement. */
  @Column(name = "crew_max")
  private Integer crewMax;

  /** Hull mass (kg). */
  @Column(name = "mass")
  private Double mass;

  /** Hull + loadout mass (kg). */
  @Column(name = "mass_total")
  private Double massTotal;

  /** Width (m). */
  @Column(name = "width")
  private Double width;

  /** Height (m). */
  @Column(name = "height")
  private Double height;

  /** Length (m). {@code length} is reserved in SQL hence the {@code _m} suffix. */
  @Column(name = "length_m")
  private Double lengthM;

  /**
   * Landing pad size class ({@code "XS"} / {@code "S"} / {@code "M"} / {@code "L"} / {@code "XL"}).
   */
  @Column(name = "pad_type")
  private String padType;

  /** Quantum fuel capacity. */
  @Column(name = "fuel_quantum")
  private Double fuelQuantum;

  /** Hydrogen fuel capacity. */
  @Column(name = "fuel_hydrogen")
  private Double fuelHydrogen;

  /** Vehicle internal inventory in SCU (Wiki's {@code vehicle_inventory}). */
  @Column(name = "vehicle_inventory_scu")
  private Double vehicleInventoryScu;

  /** Mining vehicle ore capacity. */
  @Column(name = "ore_capacity")
  private Double oreCapacity;

  /** Comma-separated SCU container sizes the vehicle can carry (e.g. {@code "1,2"}). */
  @Column(name = "container_sizes")
  private String containerSizes;

  /** Highest medical service tier this vehicle can provide. */
  @Column(name = "max_medical_tier")
  private Integer maxMedicalTier;

  /** Hull health points. */
  @Column(name = "health")
  private Integer health;

  /** Shield health points. */
  @Column(name = "shield_hp")
  private Integer shieldHp;

  // ───── 36 UEX capability flags ─────

  /** Add-on module to a parent ship (not a stand-alone vehicle). */
  @Column(name = "is_addon")
  private Boolean isAddon;

  /** Boarding-craft profile. */
  @Column(name = "is_boarding")
  private Boolean isBoarding;

  /** Bomber profile. */
  @Column(name = "is_bomber")
  private Boolean isBomber;

  /** Cargo-hauler profile. */
  @Column(name = "is_cargo")
  private Boolean isCargo;

  /** Carrier (can house sub-vessels). */
  @Column(name = "is_carrier")
  private Boolean isCarrier;

  /** Civilian (non-military) profile. */
  @Column(name = "is_civilian")
  private Boolean isCivilian;

  /** Concept ship — sold but not flyable. */
  @Column(name = "is_concept")
  private Boolean isConcept;

  /** Construction / industrial-bay-capable profile. */
  @Column(name = "is_construction")
  private Boolean isConstruction;

  /** Data-running / hacking profile. */
  @Column(name = "is_datarunner")
  private Boolean isDatarunner;

  /** Can dock with larger vessels. */
  @Column(name = "is_docking")
  private Boolean isDocking;

  /** Carries an EMP module. */
  @Column(name = "is_emp")
  private Boolean isEmp;

  /** Exploration profile. */
  @Column(name = "is_exploration")
  private Boolean isExploration;

  /** Ground vehicle (rover, tank, bike). */
  @Column(name = "is_ground_vehicle")
  private Boolean isGroundVehicle;

  /** Ships that act as hangars (can store other ships). */
  @Column(name = "is_hangar")
  private Boolean isHangar;

  /** Industrial profile (refinery / mining / construction lean). */
  @Column(name = "is_industrial")
  private Boolean isIndustrial;

  /** Quantum interdiction capability. */
  @Column(name = "is_interdiction")
  private Boolean isInterdiction;

  /** Has a loading dock (for cargo handling). */
  @Column(name = "is_loading_dock")
  private Boolean isLoadingDock;

  /** Medical / triage profile. */
  @Column(name = "is_medical")
  private Boolean isMedical;

  /** Military profile (UEE / faction-issued). */
  @Column(name = "is_military")
  private Boolean isMilitary;

  /** Mining capability. */
  @Column(name = "is_mining")
  private Boolean isMining;

  /** Passenger / transport profile. */
  @Column(name = "is_passenger")
  private Boolean isPassenger;

  /** Quantum enforcement device equipped. */
  @Column(name = "is_qed")
  private Boolean isQed;

  /** Quantum-travel capable (most ships). */
  @Column(name = "is_quantum_capable")
  private Boolean isQuantumCapable;

  /** Racing profile. */
  @Column(name = "is_racing")
  private Boolean isRacing;

  /** Refinery-capable ship. */
  @Column(name = "is_refinery")
  private Boolean isRefinery;

  /** Refuelling ship. */
  @Column(name = "is_refuel")
  private Boolean isRefuel;

  /** Field-repair ship. */
  @Column(name = "is_repair")
  private Boolean isRepair;

  /** Research / science profile. */
  @Column(name = "is_research")
  private Boolean isResearch;

  /** Salvage-capable ship. */
  @Column(name = "is_salvage")
  private Boolean isSalvage;

  /** Carries dedicated scanning gear. */
  @Column(name = "is_scanning")
  private Boolean isScanning;

  /** Science profile (overlaps research; UEX maintains both flags). */
  @Column(name = "is_science")
  private Boolean isScience;

  /** Tournament-prize / Showdown winner marker. */
  @Column(name = "is_showdown_winner")
  private Boolean isShowdownWinner;

  /** Atmosphere-capable spaceship (false for ground vehicles). */
  @Column(name = "is_spaceship")
  private Boolean isSpaceship;

  /** Starter-package eligible. */
  @Column(name = "is_starter")
  private Boolean isStarter;

  /** Stealth-profile ship. */
  @Column(name = "is_stealth")
  private Boolean isStealth;

  /** Tractor-beam-equipped. */
  @Column(name = "is_tractor_beam")
  private Boolean isTractorBeam;

  // ───── URLs ─────

  /** RSI pledge store URL. */
  @Column(name = "url_store", length = 512)
  private String urlStore;

  /** Marketing brochure URL. */
  @Column(name = "url_brochure", length = 512)
  private String urlBrochure;

  /** Ship-specific hotsite URL. */
  @Column(name = "url_hotsite", length = 512)
  private String urlHotsite;

  /** Promotional photo URL. */
  @Column(name = "url_photo", length = 512)
  private String urlPhoto;

  /** Promotional video URL. */
  @Column(name = "url_video", length = 512)
  private String urlVideo;

  /** RSI / community wiki URL. */
  @Column(name = "url_wiki", length = 512)
  private String urlWiki;

  // ───── multi-language descriptions ─────

  /**
   * English description. Replaces the legacy synthesized {@code description} column, which V125
   * dropped on 2026-06-01 together with its JPA field — no {@code description} member exists on
   * this entity any more.
   */
  @Column(name = "description_en", columnDefinition = "TEXT")
  private String descriptionEn;

  /** German description (filled by R4 Wiki sync; UEX does not expose a DE field today). */
  @Column(name = "description_de", columnDefinition = "TEXT")
  private String descriptionDe;

  // ───── provenance ─────

  /** Last successful UEX sync touch. */
  @Column(name = "uex_synced_at")
  private Instant uexSyncedAt;

  /** Last successful SC Wiki sync touch (R4+). */
  @Column(name = "scwiki_synced_at")
  private Instant scwikiSyncedAt;

  /** UEX soft-delete marker. */
  @Column(name = "uex_deleted_at")
  private Instant uexDeletedAt;

  /** SC Wiki soft-delete marker. */
  @Column(name = "scwiki_deleted_at")
  private Instant scwikiDeletedAt;

  /** Which external catalogues have written; defaults to UEX_ONLY for R2 inserts. */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_systems", nullable = false, length = 16)
  private GameItemSourceSystem sourceSystems = GameItemSourceSystem.UEX_ONLY;

  // ───── KRT P4K Reader source lane (catalog import) ─────

  /**
   * DataForge {@code __ref} asset GUID observed by the KRT P4K Reader import for this ship. Kept
   * alongside (not in place of) {@link #externalUuid}: the importer backfills {@code external_uuid}
   * only when it is null and unclaimed, but always records the P4K-observed GUID here so a UUID
   * disagreement stays auditable. Not UNIQUE.
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
