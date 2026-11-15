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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroup;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroupType;
import de.greluc.krt.profit.basetool.backend.patchchange.api.WipeGroupType.Source;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The catalogue types each wipe group claims, and the group of one stock row (REQ-PATCH-010).
 *
 * <p>A material row falls into the group of its material type. An item with a P4K type is matched
 * by its type and sub-type, then its type, then the longest matching type prefix; an untyped item
 * by the longest matching SC Wiki classification prefix, then its kind. An item nothing claims is
 * an {@link WipeGroup#OTHER_ITEMS other item}, so a new type needs no maintenance.
 */
final class WipeGroupTypes {

  /** The types every group claims, in the groups' declaration order. */
  private static final Map<WipeGroup, List<WipeGroupType>> TYPES = declareTypes();

  /** The group of every type key, by the catalogue fact it is compared with. */
  private static final Map<Source, Map<String, WipeGroup>> GROUP_BY_KEY = indexKeys();

  /** Not instantiable. */
  private WipeGroupTypes() {}

  /**
   * Returns the catalogue types a group claims.
   *
   * @param group the wipe group
   * @return its types; none for {@link WipeGroup#OTHER_ITEMS}, which takes what no group claims
   */
  static @NotNull @Unmodifiable List<WipeGroupType> typesOf(@NotNull WipeGroup group) {
    return TYPES.get(group);
  }

  /**
   * Returns the stock group of one row.
   *
   * @param row the row with its catalogue facts
   * @return the group; never {@link WipeGroup#IN_GAME_SHIPS}
   */
  static @NotNull WipeGroup groupOf(@NotNull StockWipeRow row) {
    if (row.materialId() != null) {
      return orOther(
          exact(
              Source.MATERIAL_TYPE, row.materialType() == null ? null : row.materialType().name()));
    }
    String type = row.itemType();
    if (type != null) {
      WipeGroup bySubType =
          row.itemSubType() == null
              ? null
              : exact(Source.ITEM_SUB_TYPE, type + ":" + row.itemSubType());
      if (bySubType != null) {
        return bySubType;
      }
      WipeGroup byType = exact(Source.ITEM_TYPE, type);
      return byType != null ? byType : orOther(longestPrefix(Source.ITEM_TYPE_PREFIX, type));
    }
    WipeGroup byClassification =
        longestPrefix(Source.WIKI_CLASSIFICATION_PREFIX, row.itemClassification());
    if (byClassification != null) {
      return byClassification;
    }
    return orOther(exact(Source.ITEM_KIND, row.itemKind() == null ? null : row.itemKind().name()));
  }

  /**
   * Looks a key up among one source's exact keys.
   *
   * @param source the catalogue fact
   * @param key the row's value, or {@code null}
   * @return the claiming group, or {@code null}
   */
  private static @Nullable WipeGroup exact(@NotNull Source source, @Nullable String key) {
    return key == null ? null : GROUP_BY_KEY.get(source).get(key);
  }

  /**
   * Finds the longest of one source's prefixes the value starts with.
   *
   * @param source the catalogue fact
   * @param value the row's value, or {@code null}
   * @return the claiming group, or {@code null}
   */
  private static @Nullable WipeGroup longestPrefix(@NotNull Source source, @Nullable String value) {
    if (value == null) {
      return null;
    }
    return GROUP_BY_KEY.get(source).entrySet().stream()
        .filter(entry -> value.startsWith(entry.getKey()))
        .max(Comparator.comparingInt(entry -> entry.getKey().length()))
        .map(Map.Entry::getValue)
        .orElse(null);
  }

  /**
   * Falls back to the other items.
   *
   * @param group a matched group, or {@code null}
   * @return the group, or {@link WipeGroup#OTHER_ITEMS}
   */
  private static @NotNull WipeGroup orOther(@Nullable WipeGroup group) {
    return group == null ? WipeGroup.OTHER_ITEMS : group;
  }

  /**
   * Declares the types of every group (REQ-PATCH-010).
   *
   * @return the types by group
   */
  private static @NotNull Map<WipeGroup, List<WipeGroupType>> declareTypes() {
    Map<WipeGroup, List<WipeGroupType>> types = new EnumMap<>(WipeGroup.class);
    types.put(
        WipeGroup.CONSUMABLES,
        concat(
            of(
                Source.ITEM_SUB_TYPE,
                "WeaponPersonal:Grenade",
                "WeaponAttachment:Magazine",
                "WeaponAttachment:Missile",
                "Misc:Consumable"),
            of(Source.ITEM_TYPE, "FPS_Consumable", "Food", "Drink", "Bottle"),
            of(
                Source.WIKI_CLASSIFICATION_PREFIX,
                "FPS.Consumable",
                "FPS.WeaponAttachment.Magazine"),
            of(Source.ITEM_KIND, "FOOD")));
    types.put(
        WipeGroup.FPS_EQUIPMENT,
        concat(
            of(Source.ITEM_TYPE, "WeaponPersonal", "WeaponAttachment", "Suit"),
            of(Source.ITEM_TYPE_PREFIX, "Char_Armor_", "Char_Clothing_", "Char_Accessory_"),
            of(Source.WIKI_CLASSIFICATION_PREFIX, "FPS.Armor", "FPS.Clothing", "FPS.Weapon"),
            of(Source.ITEM_KIND, "WEAPON", "WEAPON_ATTACHMENT", "ARMOR", "CLOTHING")));
    types.put(
        WipeGroup.SHIP_COMPONENTS,
        concat(
            of(
                Source.ITEM_TYPE,
                Stream.concat(
                        Arrays.stream(ShipComponentType.values())
                            .map(ShipComponentType::getWikiType),
                        Stream.of("GroundVehicleMissileLauncher"))
                    .toArray(String[]::new)),
            of(Source.WIKI_CLASSIFICATION_PREFIX, "Ship."),
            of(Source.ITEM_KIND, "VEHICLE_ITEM", "VEHICLE_WEAPON")));
    types.put(WipeGroup.MATERIALS_RAW, of(Source.MATERIAL_TYPE, "RAW"));
    types.put(WipeGroup.MATERIALS_REFINED, of(Source.MATERIAL_TYPE, "REFINED"));
    types.put(WipeGroup.MATERIALS_NO_REFINE, of(Source.MATERIAL_TYPE, "NO_REFINE"));
    types.put(WipeGroup.OTHER_ITEMS, List.of());
    types.put(WipeGroup.IN_GAME_SHIPS, of(Source.SHIP_ORIGIN, ShipOrigin.IN_GAME.name()));
    return Collections.unmodifiableMap(types);
  }

  /**
   * Indexes the declared types; a key two groups claim fails the class's initialisation.
   *
   * @return the group of every key, by source
   */
  private static @NotNull Map<Source, Map<String, WipeGroup>> indexKeys() {
    Map<Source, Map<String, WipeGroup>> index = new EnumMap<>(Source.class);
    for (Source source : Source.values()) {
      index.put(
          source,
          TYPES.entrySet().stream()
              .flatMap(entry ->
                  entry.getValue().stream()
                      .filter(type -> type.source() == source)
                      .map(type -> Map.entry(type.value(), entry.getKey())))
              .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
    return Collections.unmodifiableMap(index);
  }

  /**
   * Builds the types of one source.
   *
   * @param source the catalogue fact
   * @param values the keys
   * @return the types
   */
  private static @NotNull List<WipeGroupType> of(
      @NotNull Source source, @NotNull String... values) {
    return Arrays.stream(values).map(value -> new WipeGroupType(source, value)).toList();
  }

  /**
   * Joins the types of several sources in order.
   *
   * @param parts the types per source
   * @return the joined types
   */
  @SafeVarargs
  private static @NotNull List<WipeGroupType> concat(@NotNull List<WipeGroupType>... parts) {
    List<WipeGroupType> joined = new ArrayList<>();
    Arrays.stream(parts).forEach(joined::addAll);
    return List.copyOf(joined);
  }
}
