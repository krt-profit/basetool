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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.With;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * SC Wiki item payload of {@code GET /api/items/{uuid}}, used by {@code ScWikiItemSyncService} to
 * fill the Wiki-owned columns of an existing {@code game_item}.
 *
 * <p>{@link #size} is a raw string the sync parses into {@code size_class}; {@link #description}
 * maps locale to text.
 *
 * @param uuid in-game asset UUID (the join key against {@code game_item.external_uuid})
 * @param slug Wiki URL slug
 * @param name display name
 * @param className RSI engine class name
 * @param classification classification path, e.g. {@code "FPS.Armor.Helmet"}
 * @param classificationLabel localised classification label
 * @param type Wiki type field
 * @param typeLabel Wiki type display label
 * @param subType Wiki sub-type field
 * @param subTypeLabel Wiki sub-type display label
 * @param size raw size token, parsed to an integer size class by the sync
 * @param grade item grade
 * @param itemClass the in-game component class, e.g. {@code "Military"}, mapped by the sync
 * @param rarity item rarity
 * @param mass mass in kg
 * @param dimension bounding-box dimensions
 * @param manufacturer manufacturer reference (bound, not written)
 * @param description locale → description text map ({@code en_EN}, {@code de_DE}, …)
 * @param isBaseVariant whether this is the base variant of a family
 * @param isCraftable whether the item is craftable
 * @param version game version the row was last seen in
 * @param durability durability block ({@code health}), or {@code null}
 * @param clothing clothing block (damage resistance, temperature range), or {@code null}
 * @param suitArmor suit-armour block, the same shape as {@code clothing}, or {@code null}
 * @param personalWeapon personal-weapon block (damage per shot, rpm), or {@code null}
 * @param vehicleWeapon vehicle-weapon block (damage per shot), or {@code null}
 * @param miningLaser mining-laser block (laser power), or {@code null}
 * @param shield shield-generator block (max health), or {@code null}
 * @param powerPlant power-plant block (power segment generation), or {@code null}
 * @param cooler cooler block (coolant segment generation), or {@code null}
 * @param quantumDrive quantum-drive block (drive speed, fuel requirement), or {@code null}
 * @param radar radar block (aim-assist distances), or {@code null}
 * @param tractorBeam tractor-beam block (force, distances, volume), or {@code null}
 * @param weaponModifier weapon-modifier block (salvage multipliers), or {@code null}
 * @param radiationResistance radiation-resistance block (dissipation rate), or {@code null}
 * @param temperatureResistance temperature-resistance block (min / max), or {@code null}
 * @param isLootable whether the source can generate the item as loot
 * @param tags compatibility tags
 * @param requiredTags tags required by compatible equipment ports
 * @param ports equipment-port constraints
 * @param images attributed image references
 * @param baseVariant base item reference
 * @param variants alternate source variants
 * @param relatedItems typed size, grade, color and equipment-set relationships
 * @param vehicles vehicles with this item installed
 * @param uexPrices derived UEX terminal evidence
 * @param updatedAt source record update time
 * @param requestedIncludes relationships explicitly requested by the HTTP client
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiItemDto(
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("class_name") String className,
    @JsonProperty("classification") String classification,
    @JsonProperty("classification_label") String classificationLabel,
    @JsonProperty("type") String type,
    @JsonProperty("type_label") String typeLabel,
    @JsonProperty("sub_type") String subType,
    @JsonProperty("sub_type_label") String subTypeLabel,
    @JsonProperty("size") String size,
    @JsonProperty("grade") String grade,
    @JsonProperty("class") String itemClass,
    @JsonProperty("rarity") String rarity,
    @JsonProperty("mass") Double mass,
    @JsonProperty("dimension") ScWikiDimensionDto dimension,
    @JsonProperty("manufacturer") ScWikiItemManufacturerDto manufacturer,
    @JsonProperty("description") Map<String, String> description,
    @JsonProperty("is_base_variant") Boolean isBaseVariant,
    @JsonProperty("is_craftable") Boolean isCraftable,
    @JsonProperty("version") String version,
    @JsonProperty("durability") JsonNode durability,
    @JsonProperty("clothing") JsonNode clothing,
    @JsonProperty("suit_armor") JsonNode suitArmor,
    @JsonProperty("personal_weapon") JsonNode personalWeapon,
    @JsonProperty("vehicle_weapon") JsonNode vehicleWeapon,
    @JsonProperty("mining_laser") JsonNode miningLaser,
    @JsonProperty("shield") JsonNode shield,
    @JsonProperty("power_plant") JsonNode powerPlant,
    @JsonProperty("cooler") JsonNode cooler,
    @JsonProperty("quantum_drive") JsonNode quantumDrive,
    @JsonProperty("radar") JsonNode radar,
    @JsonProperty("tractor_beam") JsonNode tractorBeam,
    @JsonProperty("weapon_modifier") JsonNode weaponModifier,
    @JsonProperty("radiation_resistance") JsonNode radiationResistance,
    @JsonProperty("temperature_resistance") JsonNode temperatureResistance,
    @Nullable @JsonProperty("is_lootable") Boolean isLootable,
    @Nullable @JsonProperty("tags") List<String> tags,
    @Nullable @JsonProperty("required_tags") List<String> requiredTags,
    @Nullable @JsonProperty("ports") List<ItemCatalogueMetadata.Port> ports,
    @Nullable @JsonProperty("images") List<ItemCatalogueMetadata.Image> images,
    @Nullable @JsonProperty("base_variant") ItemCatalogueMetadata.ItemReference baseVariant,
    @Nullable @JsonProperty("variants") List<ItemCatalogueMetadata.ItemReference> variants,
    @Nullable @JsonProperty("related_items") ItemCatalogueMetadata.RelatedItems relatedItems,
    @Nullable @JsonProperty("vehicles") List<ItemCatalogueMetadata.VehicleReference> vehicles,
    @Nullable @JsonProperty("uex_prices") ScWikiUexPriceEvidenceDto uexPrices,
    @Nullable @JsonProperty("updated_at") Instant updatedAt,
    @Nullable @JsonIgnore @With Set<ItemCatalogueMetadata.Relationship> requestedIncludes)
    implements ScWikiRow {

  /**
   * Projects optional Wiki details with explicit provenance and relationship load state.
   *
   * @return immutable source metadata
   */
  public ItemCatalogueMetadata metadata() {
    return new ItemCatalogueMetadata(
        ItemCatalogueMetadata.Source.SC_WIKI,
        version,
        requestedIncludes == null ? Set.of() : requestedIncludes,
        geometry(),
        isLootable,
        immutable(tags),
        immutable(requiredTags),
        immutable(ports),
        immutable(images),
        baseVariant,
        immutable(variants),
        relatedItems,
        immutable(vehicles),
        uexPrices == null ? List.of() : uexPrices.purchase(),
        updatedAt);
  }

  /**
   * Preserves separate physical, cargo and display geometry contracts.
   *
   * @return geometry when the source supplied its dimension block
   */
  @Nullable
  private ItemCatalogueMetadata.Geometry geometry() {
    if (dimension == null) {
      return null;
    }
    ItemCatalogueMetadata.Axes physical =
        dimension.width() == null && dimension.height() == null && dimension.length() == null
            ? null
            : new ItemCatalogueMetadata.Axes(
                dimension.width(), dimension.height(), dimension.length());
    return new ItemCatalogueMetadata.Geometry(
        physical, axes(dimension.cargo()), axes(dimension.ui()), dimension.volumeScu());
  }

  /**
   * Converts one optional source dimension block.
   *
   * @param source source axes
   * @return typed axes in metres
   */
  @Nullable
  private static ItemCatalogueMetadata.Axes axes(@Nullable ScWikiDimensionDto.Axes source) {
    return source == null
        ? null
        : new ItemCatalogueMetadata.Axes(source.width(), source.height(), source.length());
  }

  /**
   * Copies an optional source collection.
   *
   * @param <T> element type
   * @param values source values
   * @return immutable source values
   */
  private static <T> List<T> immutable(@Nullable List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
