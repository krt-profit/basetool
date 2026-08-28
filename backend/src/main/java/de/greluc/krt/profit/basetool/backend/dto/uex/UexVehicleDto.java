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

package de.greluc.krt.profit.basetool.backend.dto.uex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound JSON record for UEX Corp's {@code /vehicles} endpoint. Mapped to the project's own {@code
 * ShipType} entity by {@code UexVehicleService}.
 *
 * <p>R2 expansion (SC_WIKI_SYNC_PLAN.md §6.5): the original three-field projection has been
 * replaced by the full UEX vehicle payload — integer id + in-game UUID + 36 {@code is_*} capability
 * flags + dimensions + fuel + urls. The hardened {@code UexVehicleService} uses {@code uuid} as the
 * primary join key (falling back to {@code id} then case-insensitive {@code name}) and writes every
 * column it carries onto {@code ship_type}. {@code uuid} is captured as a {@link String} for the
 * same reason as {@code UexItemDto} — UEX returns an empty string for ~31% of vehicles.
 *
 * <p><b>Bound to what UEX actually serves (REQ-DATA-015 / ADR-0148).</b> Eleven components were
 * removed on 2026-08-28 after a field-by-field comparison against the live endpoint: {@code
 * crew_min}, {@code crew_max}, {@code mass_total}, {@code vehicle_inventory}, {@code ore_capacity},
 * {@code max_medical_tier}, {@code health}, {@code shield_hp}, {@code url_wiki}, {@code
 * description} and {@code description_de} are not in the payload and never were during this
 * record's lifetime. Because the record is {@link JsonIgnoreProperties}{@code (ignoreUnknown =
 * true)} they decoded to {@code null}, and {@code UexVehicleService} wrote every one of them onto
 * {@code ship_type} — which not only left eight columns permanently empty but also cleared {@code
 * vehicle_inventory_scu} and {@code description_en} after each Wiki vehicle sync had filled them.
 * The crew range survives the removal: UEX serves it as the compact {@code crew} string ({@code
 * "1"}, {@code "1,2"}), which {@code UexValues.parseCrew} splits back into min / max.
 *
 * @param id UEX integer vehicle id (stable across runs)
 * @param uuid in-game RSI asset UUID — empty string for vehicles UEX has not catalogued yet
 * @param name short name, e.g. {@code "100i"}
 * @param nameFull full marketing name, e.g. {@code "Origin 100i"}
 * @param slug kebab-case URL slug
 * @param companyName denormalised manufacturer name
 * @param scu cargo SCU (legacy field kept for back-compat with the synthesized description)
 * @param crew legacy crew text field — kept for back-compat; new code reads {@code crewMin}/{@code
 *     crewMax}
 * @param mass hull mass (kg)
 * @param width metres
 * @param height metres
 * @param length metres ({@code length} reserved in SQL — entity column is {@code length_m})
 * @param padType landing pad size class ({@code "XS"} / {@code "S"} / {@code "M"} / {@code "L"} /
 *     {@code "XL"})
 * @param fuelQuantum quantum fuel capacity
 * @param fuelHydrogen hydrogen fuel capacity
 * @param containerSizes comma-separated SCU container sizes (e.g. {@code "1,2"})
 * @param urlStore RSI pledge store URL
 * @param urlBrochure marketing brochure URL
 * @param urlHotsite ship-specific hotsite URL
 * @param urlPhoto promotional photo URL
 * @param urlVideo promotional video URL
 * @param isAddon UEX flag (0/1)
 * @param isBoarding UEX flag
 * @param isBomber UEX flag
 * @param isCargo UEX flag
 * @param isCarrier UEX flag
 * @param isCivilian UEX flag
 * @param isConcept UEX flag — set on ship-sale-only concepts not yet flyable
 * @param isConstruction UEX flag
 * @param isDatarunner UEX flag
 * @param isDocking UEX flag
 * @param isEmp UEX flag
 * @param isExploration UEX flag
 * @param isGroundVehicle UEX flag
 * @param isHangar UEX flag
 * @param isIndustrial UEX flag
 * @param isInterdiction UEX flag
 * @param isLoadingDock UEX flag
 * @param isMedical UEX flag
 * @param isMilitary UEX flag
 * @param isMining UEX flag
 * @param isPassenger UEX flag
 * @param isQed UEX flag — quantum enforcement device
 * @param isQuantumCapable UEX flag
 * @param isRacing UEX flag
 * @param isRefinery UEX flag
 * @param isRefuel UEX flag
 * @param isRepair UEX flag
 * @param isResearch UEX flag
 * @param isSalvage UEX flag
 * @param isScanning UEX flag
 * @param isScience UEX flag
 * @param isShowdownWinner UEX flag — marker for tournament-prize ships
 * @param isSpaceship UEX flag
 * @param isStarter UEX flag — set on starter-package eligible ships
 * @param isStealth UEX flag
 * @param isTractorBeam UEX flag
 * @param idCompany UEX integer company id of the manufacturer — the stable key the vehicle sync
 *     resolves the manufacturer through, so a brand UEX splits across several company records still
 *     reunites on one manufacturer row (ADR-0023)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UexVehicleDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("uuid") String uuid,
    @JsonProperty("name") String name,
    @JsonProperty("name_full") String nameFull,
    @JsonProperty("slug") String slug,
    @JsonProperty("company_name") String companyName,
    @JsonProperty("scu") Integer scu,
    @JsonProperty("crew") String crew,
    @JsonProperty("mass") Double mass,
    @JsonProperty("width") Double width,
    @JsonProperty("height") Double height,
    @JsonProperty("length") Double length,
    @JsonProperty("pad_type") String padType,
    @JsonProperty("fuel_quantum") Double fuelQuantum,
    @JsonProperty("fuel_hydrogen") Double fuelHydrogen,
    @JsonProperty("container_sizes") String containerSizes,
    @JsonProperty("url_store") String urlStore,
    @JsonProperty("url_brochure") String urlBrochure,
    @JsonProperty("url_hotsite") String urlHotsite,
    @JsonProperty("url_photo") String urlPhoto,
    @JsonProperty("url_video") String urlVideo,
    @JsonProperty("is_addon") Integer isAddon,
    @JsonProperty("is_boarding") Integer isBoarding,
    @JsonProperty("is_bomber") Integer isBomber,
    @JsonProperty("is_cargo") Integer isCargo,
    @JsonProperty("is_carrier") Integer isCarrier,
    @JsonProperty("is_civilian") Integer isCivilian,
    @JsonProperty("is_concept") Integer isConcept,
    @JsonProperty("is_construction") Integer isConstruction,
    @JsonProperty("is_datarunner") Integer isDatarunner,
    @JsonProperty("is_docking") Integer isDocking,
    @JsonProperty("is_emp") Integer isEmp,
    @JsonProperty("is_exploration") Integer isExploration,
    @JsonProperty("is_ground_vehicle") Integer isGroundVehicle,
    @JsonProperty("is_hangar") Integer isHangar,
    @JsonProperty("is_industrial") Integer isIndustrial,
    @JsonProperty("is_interdiction") Integer isInterdiction,
    @JsonProperty("is_loading_dock") Integer isLoadingDock,
    @JsonProperty("is_medical") Integer isMedical,
    @JsonProperty("is_military") Integer isMilitary,
    @JsonProperty("is_mining") Integer isMining,
    @JsonProperty("is_passenger") Integer isPassenger,
    @JsonProperty("is_qed") Integer isQed,
    @JsonProperty("is_quantum_capable") Integer isQuantumCapable,
    @JsonProperty("is_racing") Integer isRacing,
    @JsonProperty("is_refinery") Integer isRefinery,
    @JsonProperty("is_refuel") Integer isRefuel,
    @JsonProperty("is_repair") Integer isRepair,
    @JsonProperty("is_research") Integer isResearch,
    @JsonProperty("is_salvage") Integer isSalvage,
    @JsonProperty("is_scanning") Integer isScanning,
    @JsonProperty("is_science") Integer isScience,
    @JsonProperty("is_showdown_winner") Integer isShowdownWinner,
    @JsonProperty("is_spaceship") Integer isSpaceship,
    @JsonProperty("is_starter") Integer isStarter,
    @JsonProperty("is_stealth") Integer isStealth,
    @JsonProperty("is_tractor_beam") Integer isTractorBeam,
    @JsonProperty("id_company") Integer idCompany) {}
