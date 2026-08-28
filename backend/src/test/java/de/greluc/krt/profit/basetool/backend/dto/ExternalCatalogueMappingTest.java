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

package de.greluc.krt.profit.basetool.backend.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiDimensionDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiVehicleDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexFactionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexJurisdictionDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexOutpostDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexPoiDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexSpaceStationDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexTerminalDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexVehicleDto;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins every inbound catalogue DTO to the field names its upstream endpoint actually serves.
 *
 * <p>Each record here is {@code @JsonIgnoreProperties(ignoreUnknown = true)}, which is the right
 * setting for a third-party feed but turns a mapping mistake into silence: a component bound to a
 * name the payload does not carry decodes to {@code null} on every row of every run, and the sync
 * services then write that {@code null} onto their entities. Three such mappings were live in
 * production until 2026-08-28 — {@code ScWikiDimensionDto}'s {@code x/y/z} (the Wiki serves {@code
 * width/height/length}, so {@code game_item.dimension_x/y/z} never held a value), eleven components
 * of {@link UexVehicleDto} (which additionally cleared what the SC-Wiki vehicle sync had just
 * filled in), and a scattering of {@code code} / {@code slug} fields across the UEX universe DTOs.
 * REQ-DATA-015 / ADR-0148.
 *
 * <p>The key sets below are the union of the keys each live endpoint returned on 2026-08-28. The
 * test asserts one direction only — <b>every mapped name must exist upstream</b> — because the
 * other direction (upstream fields we deliberately do not bind) is an ever-growing choice, not a
 * defect. A failure here means either the mapping is wrong or the upstream renamed something and
 * the key set needs re-capturing against the live API; it never means "add the field".
 */
class ExternalCatalogueMappingTest {

  private static final Set<String> SCWIKI_ITEM_KEYS =
      Set.of(
          "ammunition",
          "armor",
          "blueprint",
          "class",
          "class_name",
          "classification",
          "classification_label",
          "clothing",
          "cooler",
          "description",
          "description_data",
          "dimension",
          "distortion",
          "durability",
          "emission",
          "entity_tag_map",
          "entity_tags",
          "event_source",
          "food",
          "gforce_resistance",
          "grade",
          "images",
          "interactions",
          "inventory",
          "is_base_variant",
          "is_craftable",
          "is_lootable",
          "jump_drive",
          "laser_pointer",
          "link",
          "magazine",
          "manufacturer",
          "manufacturer_description",
          "mass",
          "max_mounts",
          "max_size",
          "min_size",
          "name",
          "personal_weapon",
          "ports",
          "position",
          "radar",
          "radiation_resistance",
          "rarity",
          "required_tags",
          "resource_network",
          "shield",
          "shops",
          "size",
          "slug",
          "sub_type",
          "sub_type_label",
          "suit_armor",
          "tags",
          "temperature",
          "temperature_resistance",
          "turret",
          "type",
          "type_label",
          "type_web_url",
          "uex_prices",
          "updated_at",
          "uuid",
          "variants",
          "vehicle_weapon",
          "version",
          "weapon_modifier",
          "web_url");

  private static final Set<String> SCWIKI_ITEM_DIMENSION_KEYS =
      Set.of(
          "cargo_dimension",
          "dimensions",
          "height",
          "length",
          "true_dimension",
          "ui_dimension",
          "volume",
          "volume_converted",
          "volume_converted_unit",
          "width");

  private static final Set<String> SCWIKI_VEHICLE_KEYS =
      Set.of(
          "afterburner",
          "agility",
          "armor",
          "career",
          "cargo_capacity",
          "cargo_grids",
          "cargo_limits",
          "chassis_id",
          "class_name",
          "cooling",
          "crew",
          "cross_section",
          "cross_section_max",
          "damage_limits",
          "description",
          "dimension",
          "drive",
          "emission",
          "foci",
          "fuel",
          "game_description",
          "game_name",
          "health",
          "id",
          "images",
          "insurance",
          "inventory_containers",
          "is_gravlev",
          "is_power_suit",
          "is_spaceship",
          "is_vehicle",
          "link",
          "loaner",
          "manufacturer",
          "mass",
          "mass_hull",
          "mass_loadout",
          "mass_total",
          "max_medical_tier",
          "msrp",
          "name",
          "no_fuel_params",
          "ore_capacity",
          "parts",
          "penetration_multiplier",
          "pledge_url",
          "port_tags",
          "ports",
          "power",
          "power_pools",
          "production_note",
          "production_status",
          "propulsion",
          "quantum",
          "relay_network",
          "role",
          "seating",
          "shield",
          "shield_face_type",
          "shield_hp",
          "shipmatrix_name",
          "signature",
          "size",
          "size_class",
          "sizes",
          "skus",
          "slug",
          "speed",
          "suit_storage",
          "turrets",
          "type",
          "uex_prices",
          "updated_at",
          "uuid",
          "vehicle_inventory",
          "version",
          "weapon_storage",
          "weaponry",
          "web_url");

  private static final Set<String> SCWIKI_COMMODITY_KEYS =
      Set.of(
          "box_sizes_scu",
          "commodity_groups",
          "density_g_per_cc",
          "description",
          "display_name",
          "has_default_cargo_containers",
          "has_fps_mineables",
          "has_ground_vehicle_mineables",
          "has_harvestables",
          "has_salvage",
          "has_ship_mineables",
          "images",
          "instability",
          "is_mineable",
          "key",
          "kind",
          "link",
          "locations",
          "methods",
          "name",
          "refined_version",
          "resistance",
          "signature",
          "slug",
          "systems",
          "tier",
          "uuid",
          "validate_default_cargo_box",
          "volatility",
          "volatility_health_decay_per_second",
          "web_url");

  private static final Set<String> SCWIKI_MANUFACTURER_KEYS =
      Set.of("code", "link", "name", "uuid");

  private static final Set<String> UEX_VEHICLE_KEYS =
      Set.of(
          "company_name",
          "container_sizes",
          "crew",
          "date_added",
          "date_modified",
          "fuel_hydrogen",
          "fuel_quantum",
          "game_version",
          "height",
          "id",
          "id_company",
          "id_parent",
          "ids_vehicles_loaners",
          "is_addon",
          "is_boarding",
          "is_bomber",
          "is_cargo",
          "is_carrier",
          "is_civilian",
          "is_concept",
          "is_construction",
          "is_datarunner",
          "is_docking",
          "is_emp",
          "is_exploration",
          "is_ground_vehicle",
          "is_hangar",
          "is_industrial",
          "is_interdiction",
          "is_loading_dock",
          "is_medical",
          "is_military",
          "is_mining",
          "is_passenger",
          "is_qed",
          "is_quantum_capable",
          "is_racing",
          "is_refinery",
          "is_refuel",
          "is_repair",
          "is_research",
          "is_salvage",
          "is_scanning",
          "is_science",
          "is_showdown_winner",
          "is_spaceship",
          "is_starter",
          "is_stealth",
          "is_tractor_beam",
          "length",
          "mass",
          "name",
          "name_full",
          "pad_type",
          "scu",
          "slug",
          "url_brochure",
          "url_hotsite",
          "url_photo",
          "url_photos",
          "url_store",
          "url_video",
          "uuid",
          "width");

  private static final Set<String> UEX_COMMODITY_KEYS =
      Set.of(
          "code",
          "date_added",
          "date_modified",
          "id",
          "id_item",
          "id_parent",
          "ids_moons",
          "ids_orbits",
          "ids_planets",
          "ids_poi",
          "ids_star_systems",
          "is_available",
          "is_available_live",
          "is_buggy",
          "is_buyable",
          "is_explosive",
          "is_extractable",
          "is_fuel",
          "is_harvestable",
          "is_illegal",
          "is_inert",
          "is_mineral",
          "is_pure",
          "is_raw",
          "is_refinable",
          "is_refined",
          "is_sellable",
          "is_temporary",
          "is_visible",
          "is_volatile_qt",
          "is_volatile_time",
          "kind",
          "name",
          "price_buy",
          "price_sell",
          "uuid",
          "weight_scu",
          "wiki");

  private static final Set<String> UEX_FACTION_KEYS =
      Set.of(
          "date_added",
          "date_modified",
          "id",
          "ids_factions_friendly",
          "ids_factions_hostile",
          "ids_star_systems",
          "is_bounty_hunting",
          "is_piracy",
          "name",
          "wiki");

  private static final Set<String> UEX_JURISDICTION_KEYS =
      Set.of(
          "date_added",
          "date_modified",
          "faction_name",
          "id",
          "id_faction",
          "is_available",
          "is_available_live",
          "is_default",
          "is_visible",
          "name",
          "nickname",
          "wiki");

  private static final Set<String> UEX_OUTPOST_KEYS =
      Set.of(
          "date_added",
          "date_modified",
          "faction_name",
          "has_cargo_center",
          "has_clinic",
          "has_docking_port",
          "has_food",
          "has_freight_elevator",
          "has_gravity",
          "has_habitation",
          "has_loading_dock",
          "has_quantum_marker",
          "has_refinery",
          "has_refuel",
          "has_repair",
          "has_shops",
          "has_trade_terminal",
          "id",
          "id_faction",
          "id_jurisdiction",
          "id_moon",
          "id_orbit",
          "id_planet",
          "id_star_system",
          "is_armistice",
          "is_available",
          "is_available_live",
          "is_decommissioned",
          "is_default",
          "is_landable",
          "is_monitored",
          "is_visible",
          "jurisdiction_name",
          "moon_name",
          "name",
          "nickname",
          "orbit_name",
          "pad_types",
          "planet_name",
          "star_system_name");

  private static final Set<String> UEX_POI_KEYS =
      Set.of(
          "city_name",
          "date_added",
          "date_modified",
          "faction_name",
          "has_cargo_center",
          "has_clinic",
          "has_docking_port",
          "has_food",
          "has_freight_elevator",
          "has_gravity",
          "has_habitation",
          "has_loading_dock",
          "has_quantum_marker",
          "has_refinery",
          "has_refuel",
          "has_repair",
          "has_shops",
          "has_trade_terminal",
          "id",
          "id_city",
          "id_faction",
          "id_jurisdiction",
          "id_moon",
          "id_orbit",
          "id_outpost",
          "id_planet",
          "id_space_station",
          "id_star_system",
          "is_armistice",
          "is_available",
          "is_available_live",
          "is_decommissioned",
          "is_default",
          "is_landable",
          "is_mining_related",
          "is_monitored",
          "is_visible",
          "jurisdiction_name",
          "moon_name",
          "name",
          "nickname",
          "orbit_name",
          "outpost_name",
          "pad_types",
          "planet_name",
          "space_station_name",
          "star_system_name",
          "subtype",
          "type");

  private static final Set<String> UEX_SPACE_STATION_KEYS =
      Set.of(
          "city_name",
          "date_added",
          "date_modified",
          "faction_name",
          "has_cargo_center",
          "has_clinic",
          "has_docking_port",
          "has_food",
          "has_freight_elevator",
          "has_gravity",
          "has_habitation",
          "has_loading_dock",
          "has_quantum_marker",
          "has_refinery",
          "has_refuel",
          "has_repair",
          "has_shops",
          "has_trade_terminal",
          "id",
          "id_city",
          "id_faction",
          "id_jurisdiction",
          "id_moon",
          "id_orbit",
          "id_planet",
          "id_star_system",
          "is_armistice",
          "is_available",
          "is_available_live",
          "is_decommissioned",
          "is_default",
          "is_jump_point",
          "is_lagrange",
          "is_landable",
          "is_monitored",
          "is_visible",
          "jurisdiction_name",
          "moon_name",
          "name",
          "nickname",
          "orbit_name",
          "pad_types",
          "planet_name",
          "star_system_name");

  private static final Set<String> UEX_TERMINAL_KEYS =
      Set.of(
          "city_name",
          "code",
          "company_name",
          "contact_url",
          "date_added",
          "date_modified",
          "displayname",
          "faction_name",
          "fullname",
          "game_version",
          "has_docking_port",
          "has_freight_elevator",
          "has_loading_dock",
          "id",
          "id_city",
          "id_company",
          "id_faction",
          "id_moon",
          "id_orbit",
          "id_outpost",
          "id_planet",
          "id_poi",
          "id_space_station",
          "id_star_system",
          "is_affinity_influenceable",
          "is_auto_load",
          "is_available",
          "is_available_live",
          "is_cargo_center",
          "is_default_system",
          "is_food",
          "is_habitation",
          "is_jump_point",
          "is_medical",
          "is_nqa",
          "is_player_owned",
          "is_refinery",
          "is_refuel",
          "is_repair",
          "is_shop_fps",
          "is_shop_vehicle",
          "is_visible",
          "max_container_size",
          "mcs",
          "moon_name",
          "name",
          "nickname",
          "orbit_name",
          "outpost_name",
          "planet_name",
          "screenshot",
          "screenshot_author",
          "screenshot_full",
          "space_station_name",
          "star_system_name",
          "type");

  @Test
  @DisplayName("SC Wiki item DTOs bind only names the /api/items payload carries")
  void scWikiItemDtos_bindOnlyServedNames() {
    assertMapsOnlyServedNames(ScWikiItemDto.class, SCWIKI_ITEM_KEYS);
    // The regression this file exists for: x/y/z is not how the Wiki names a bounding box.
    assertMapsOnlyServedNames(ScWikiDimensionDto.class, SCWIKI_ITEM_DIMENSION_KEYS);
  }

  @Test
  @DisplayName("the other SC Wiki list DTOs bind only names their payloads carry")
  void scWikiListDtos_bindOnlyServedNames() {
    assertMapsOnlyServedNames(ScWikiVehicleDto.class, SCWIKI_VEHICLE_KEYS);
    assertMapsOnlyServedNames(ScWikiCommodityDto.class, SCWIKI_COMMODITY_KEYS);
    assertMapsOnlyServedNames(ScWikiManufacturerDto.class, SCWIKI_MANUFACTURER_KEYS);
  }

  @Test
  @DisplayName("UEX vehicle + commodity DTOs bind only names their payloads carry")
  void uexCatalogueDtos_bindOnlyServedNames() {
    assertMapsOnlyServedNames(UexVehicleDto.class, UEX_VEHICLE_KEYS);
    assertMapsOnlyServedNames(UexCommodityDto.class, UEX_COMMODITY_KEYS);
  }

  @Test
  @DisplayName("UEX universe DTOs bind only names their payloads carry")
  void uexUniverseDtos_bindOnlyServedNames() {
    assertMapsOnlyServedNames(UexFactionDto.class, UEX_FACTION_KEYS);
    assertMapsOnlyServedNames(UexJurisdictionDto.class, UEX_JURISDICTION_KEYS);
    assertMapsOnlyServedNames(UexOutpostDto.class, UEX_OUTPOST_KEYS);
    assertMapsOnlyServedNames(UexPoiDto.class, UEX_POI_KEYS);
    assertMapsOnlyServedNames(UexSpaceStationDto.class, UEX_SPACE_STATION_KEYS);
    assertMapsOnlyServedNames(UexTerminalDto.class, UEX_TERMINAL_KEYS);
  }

  /**
   * Resolves the JSON name a record component binds to.
   *
   * <p>{@code @JsonProperty} does not list {@code RECORD_COMPONENT} among its targets, so javac
   * propagates it to the generated field / accessor instead and {@link
   * RecordComponent#getAnnotation} answers {@code null} for every one of them. Reading only the
   * component would silently fall back to the Java name and pass this whole test for exactly the
   * components whose JSON name differs — which is all the ones worth checking.
   *
   * @param record the declaring record class
   * @param component the component to resolve
   * @return the {@code @JsonProperty} value where one is present, else the component's own name
   */
  private static String jsonNameOf(Class<?> record, RecordComponent component) {
    JsonProperty annotation = component.getAnnotation(JsonProperty.class);
    if (annotation == null) {
      annotation = component.getAccessor().getAnnotation(JsonProperty.class);
    }
    if (annotation == null) {
      try {
        annotation = record.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException("record component without a backing field", e);
      }
    }
    return annotation != null ? annotation.value() : component.getName();
  }

  /**
   * Asserts that every JSON name {@code record} binds appears in {@code servedKeys}.
   *
   * @param record the inbound DTO record to inspect
   * @param servedKeys the union of the keys the live endpoint returned when it was last captured
   */
  private static void assertMapsOnlyServedNames(Class<?> record, Set<String> servedKeys) {
    List<String> phantom = new ArrayList<>();
    for (RecordComponent component : record.getRecordComponents()) {
      String jsonName = jsonNameOf(record, component);
      if (!servedKeys.contains(jsonName)) {
        phantom.add(jsonName + " (-> " + component.getName() + ")");
      }
    }
    assertTrue(
        phantom.isEmpty(),
        record.getSimpleName()
            + " binds field(s) the upstream does not serve, so they decode to null on every row"
            + " and the sync writes that null onto its entity: "
            + phantom);
  }
}
