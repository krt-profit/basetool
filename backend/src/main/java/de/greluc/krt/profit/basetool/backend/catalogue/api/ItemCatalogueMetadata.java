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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Retains typed Wiki catalogue evidence without changing canonical item or price ownership.
 *
 * @param source catalogue supplying this snapshot
 * @param gameVersion patch the snapshot describes
 * @param requestedIncludes relationships explicitly requested from the source
 * @param geometry distinct physical, cargo and display geometry
 * @param lootable whether the source can generate this item as loot
 * @param tags item compatibility tags
 * @param requiredTags tags required by an equipment port
 * @param ports equipment ports and their compatibility constraints
 * @param images attributed source images
 * @param baseVariant source reference to the base item
 * @param variants source variant references
 * @param relatedItems explicit size, grade, color and set relationships
 * @param vehicles vehicles with this item installed
 * @param uexPriceEvidence UEX information imported through the Wiki
 * @param sourceUpdatedAt source record's last update
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemCatalogueMetadata(
    Source source,
    @Nullable String gameVersion,
    Set<Relationship> requestedIncludes,
    @Nullable Geometry geometry,
    @Nullable Boolean lootable,
    List<String> tags,
    List<String> requiredTags,
    List<Port> ports,
    List<Image> images,
    @Nullable ItemReference baseVariant,
    List<ItemReference> variants,
    @Nullable RelatedItems relatedItems,
    List<VehicleReference> vehicles,
    List<UexPriceEvidence> uexPriceEvidence,
    @Nullable Instant sourceUpdatedAt) {

  /** Copies the snapshot's collections so callers cannot mutate retained source evidence. */
  public ItemCatalogueMetadata {
    requestedIncludes = requestedIncludes == null ? Set.of() : Set.copyOf(requestedIncludes);
    tags = immutable(tags);
    requiredTags = immutable(requiredTags);
    ports = immutable(ports);
    images = immutable(images);
    variants = immutable(variants);
    vehicles = immutable(vehicles);
    uexPriceEvidence = immutable(uexPriceEvidence);
  }

  /** Names the catalogue supplying the item snapshot. */
  public enum Source {
    /** Community Wiki game-data catalogue. */
    SC_WIKI
  }

  /** Identifies a relationship whose empty result can be interpreted as loaded. */
  public enum Relationship {
    /** Alternate item variants. */
    VARIANTS,
    /** Explicit size, grade, color and equipment-set relationships. */
    RELATED_ITEMS,
    /** Vehicles on which the item is installed. */
    VEHICLES
  }

  /**
   * Separates geometry used by different inventory contracts.
   *
   * @param physical physical bounding box in metres
   * @param cargo cargo-grid occupancy in metres
   * @param ui inventory display dimensions in metres
   * @param volumeScu stored inventory occupancy in SCU
   */
  public record Geometry(
      @Nullable Axes physical,
      @Nullable Axes cargo,
      @Nullable Axes ui,
      @Nullable Double volumeScu) {}

  /**
   * Contains one dimension block in metres.
   *
   * @param width width in metres
   * @param height height in metres
   * @param length length in metres
   */
  public record Axes(@Nullable Double width, @Nullable Double height, @Nullable Double length) {}

  /**
   * Describes an attributed image without embedding remote media.
   *
   * @param source source attribution
   * @param originalUrl original image address
   * @param thumbnailUrl thumbnail address
   * @param originalWidth original width in pixels
   * @param originalHeight original height in pixels
   * @param thumbnailWidth thumbnail width in pixels
   * @param thumbnailHeight thumbnail height in pixels
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Image(
      @Nullable String source,
      @Nullable @JsonAlias("original_url") String originalUrl,
      @Nullable @JsonAlias("thumbnail_url") String thumbnailUrl,
      @Nullable @JsonAlias("original_width") Integer originalWidth,
      @Nullable @JsonAlias("original_height") Integer originalHeight,
      @Nullable @JsonAlias("thumbnail_width") Integer thumbnailWidth,
      @Nullable @JsonAlias("thumbnail_height") Integer thumbnailHeight) {}

  /**
   * Links a source manufacturer without substituting a local database identifier.
   *
   * @param uuid source manufacturer UUID
   * @param name display name
   * @param code manufacturer code
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ManufacturerReference(
      @Nullable UUID uuid, @Nullable String name, @Nullable String code) {}

  /**
   * Retains identity and classification for a related source item.
   *
   * @param uuid game asset UUID
   * @param name source display name
   * @param className engine class name
   * @param type source item type
   * @param subType source item subtype
   * @param classification source classification path
   * @param baseVariant whether the source marks this as the base variant
   * @param variantName source variant label
   * @param version source game version
   * @param size size class
   * @param grade source grade
   * @param itemClass source component class
   * @param manufacturer source manufacturer identity
   * @param link source API address
   * @param webUrl source browser address
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ItemReference(
      @Nullable UUID uuid,
      @Nullable String name,
      @Nullable @JsonAlias("class_name") String className,
      @Nullable String type,
      @Nullable @JsonAlias("sub_type") String subType,
      @Nullable String classification,
      @Nullable @JsonAlias("is_base_variant") Boolean baseVariant,
      @Nullable @JsonAlias("variant_name") String variantName,
      @Nullable String version,
      @Nullable Integer size,
      @Nullable String grade,
      @Nullable @JsonAlias("class") String itemClass,
      @Nullable ManufacturerReference manufacturer,
      @Nullable String link,
      @Nullable @JsonAlias("web_url") String webUrl) {}

  /**
   * Keeps source relationship categories separate instead of inferring families from names.
   *
   * @param setName equipment-set label
   * @param baseItem base item reference
   * @param bySize items differing by size
   * @param byGrade items differing by grade
   * @param byColor items differing by color
   * @param setItems items in the equipment set
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RelatedItems(
      @Nullable @JsonAlias("set_name") String setName,
      @Nullable @JsonAlias("base_item") ItemReference baseItem,
      @JsonAlias("by_size") List<ItemReference> bySize,
      @JsonAlias("by_grade") List<ItemReference> byGrade,
      @JsonAlias("by_color") List<ItemReference> byColor,
      @JsonAlias("set_items") List<ItemReference> setItems) {

    /** Copies each relationship category independently. */
    public RelatedItems {
      bySize = immutable(bySize);
      byGrade = immutable(byGrade);
      byColor = immutable(byColor);
      setItems = immutable(setItems);
    }
  }

  /**
   * Links a vehicle that has this item installed.
   *
   * @param uuid game vehicle UUID
   * @param name vehicle name
   * @param className engine class name
   * @param version game version
   * @param link source API address
   * @param webUrl source browser address
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VehicleReference(
      @Nullable UUID uuid,
      @Nullable String name,
      @Nullable @JsonAlias("class_name") String className,
      @Nullable String version,
      @Nullable String link,
      @Nullable @JsonAlias("web_url") String webUrl) {}

  /**
   * Describes one equipment port and its supported items.
   *
   * @param name engine port name
   * @param displayName source display label
   * @param position equipment position
   * @param size nominal size
   * @param sizes permitted size interval
   * @param compatibleTypes permitted type and subtype pairs
   * @param tags port tags
   * @param requiredTags required item tags
   * @param editable whether the source allows changing the item
   * @param equippedItemUuid installed item asset UUID
   * @param ports nested equipment ports
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Port(
      @Nullable String name,
      @Nullable @JsonAlias("display_name") String displayName,
      @Nullable String position,
      @Nullable Integer size,
      @Nullable PortSizes sizes,
      @JsonAlias("compatible_types") List<CompatibleType> compatibleTypes,
      List<String> tags,
      @JsonAlias("required_tags") List<String> requiredTags,
      @Nullable Boolean editable,
      @Nullable @JsonAlias("equipped_item_uuid") UUID equippedItemUuid,
      List<Port> ports) {

    /** Copies compatibility constraints and nested port collections. */
    public Port {
      compatibleTypes = immutable(compatibleTypes);
      tags = immutable(tags);
      requiredTags = immutable(requiredTags);
      ports = immutable(ports);
    }
  }

  /**
   * Defines an equipment port's supported size interval.
   *
   * @param min minimum size
   * @param max maximum size
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PortSizes(@Nullable Integer min, @Nullable Integer max) {}

  /**
   * Defines an equipment type and its accepted subtypes.
   *
   * @param type source item type
   * @param subTypes source item subtypes
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CompatibleType(
      @Nullable String type, @JsonAlias("sub_types") List<String> subTypes) {

    /** Copies the accepted subtype collection. */
    public CompatibleType {
      subTypes = immutable(subTypes);
    }
  }

  /**
   * Identifies the Wiki location linked by a UEX terminal.
   *
   * @param uuid Wiki location UUID
   * @param name source location name
   * @param slug source location slug
   * @param typeName source location kind
   * @param parentName source parent location name
   * @param starSystemName source system name
   * @param link source API address
   * @param webUrl source browser address
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LocationEvidence(
      @Nullable UUID uuid,
      @Nullable String name,
      @Nullable String slug,
      @Nullable @JsonAlias("type_name") String typeName,
      @Nullable @JsonAlias("parent_name") String parentName,
      @Nullable @JsonAlias("star_system_name") String starSystemName,
      @Nullable String link,
      @Nullable @JsonAlias("web_url") String webUrl) {}

  /** Names the derivation of prices retained as source evidence. */
  public enum PriceOrigin {
    /** UEX data imported by the Wiki. */
    UEX_VIA_SC_WIKI
  }

  /**
   * Preserves derived terminal evidence without becoming a canonical price source.
   *
   * @param origin derivation of the evidence
   * @param priceBuy reported purchase price
   * @param priceSell reported sale price
   * @param priceRent reported rental price
   * @param terminalId UEX terminal ID
   * @param terminalCode UEX terminal code
   * @param terminalName source terminal label
   * @param location linked Wiki location
   * @param updatedAt UEX report timestamp
   * @param gameVersion UEX report game version
   * @param uexLink UEX item reference
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UexPriceEvidence(
      PriceOrigin origin,
      @Nullable @JsonAlias("price_buy") BigDecimal priceBuy,
      @Nullable @JsonAlias("price_sell") BigDecimal priceSell,
      @Nullable @JsonAlias("price_rent") BigDecimal priceRent,
      @Nullable @JsonAlias("terminal_id") Integer terminalId,
      @Nullable @JsonAlias("terminal_code") String terminalCode,
      @Nullable @JsonAlias("terminal_name") String terminalName,
      @Nullable @JsonAlias("starmap_location") LocationEvidence location,
      @Nullable @JsonAlias("date_updated") Instant updatedAt,
      @Nullable @JsonAlias("game_version") String gameVersion,
      @Nullable @JsonAlias("uex_link") String uexLink) {

    /** Makes the derived origin explicit when binding a Wiki response. */
    public UexPriceEvidence {
      origin = origin == null ? PriceOrigin.UEX_VIA_SC_WIKI : origin;
    }

    /**
     * Keeps this imported evidence outside authoritative price selection.
     *
     * @return false for Wiki-imported UEX prices
     */
    @JsonProperty
    public boolean authoritative() {
      return false;
    }
  }

  /**
   * Normalizes a missing source collection into an immutable empty collection.
   *
   * @param <T> element type
   * @param values optional source collection
   * @return immutable source values
   */
  private static <T> List<T> immutable(@Nullable List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
