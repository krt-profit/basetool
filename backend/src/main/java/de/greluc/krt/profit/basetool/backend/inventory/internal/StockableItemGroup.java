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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The groups of catalogue item types the Lager books as item stock beside every blueprint output
 * (REQ-INV-029, ADR-0370), keyed on the item's P4K type ({@code GameItem#wikiType}) and sub-type.
 *
 * <p>Clothing, paints, decoration, turrets, consumables and a ship's fixed parts belong to no
 * group, so the Lager books them only when a blueprint produces them.
 */
@RequiredArgsConstructor
enum StockableItemGroup {

  /** Hand-held weapons, knives and gadgets such as multi-tools; grenades and flares are used up. */
  FPS_WEAPONS(Set.of("WeaponPersonal"), Set.of("Grenade")),

  /**
   * Optics, barrels, underbarrel and utility attachments; magazines and missiles are ammunition.
   */
  WEAPON_ATTACHMENTS(Set.of("WeaponAttachment"), Set.of("Magazine", "Missile")),

  /** Helmets, torso, arm and leg armour, backpacks and undersuits. */
  ARMOUR(
      Set.of(
          "Char_Armor_Helmet",
          "Char_Armor_Torso",
          "Char_Armor_Arms",
          "Char_Armor_Legs",
          "Char_Armor_Backpack",
          "Char_Armor_Undersuit"),
      Set.of()),

  /** Jetpacks. */
  JETPACKS(Set.of("Suit"), Set.of()),

  /**
   * Every ship component and ship weapon the Lager labels as a component (REQ-INV-061) except
   * turrets, which are fixed parts of their ship, plus ground-vehicle missile launchers.
   */
  SHIP_COMPONENTS(shipComponentTypesWithoutTurrets("GroundVehicleMissileLauncher"), Set.of()),

  /** Mining modules, mining gadgets and salvage modifiers. */
  MINING_AND_SALVAGE_MODULES(Set.of("MiningModifier", "Gadget", "SalvageModifier"), Set.of()),

  /** Self-storage containers such as the Stor*All boxes. */
  STORAGE_CONTAINERS(Set.of("InventoryContainer"), Set.of()),

  /**
   * Loot and collectibles such as artifacts, medals, ingots and creature parts; mineable gems are
   * materials and food is used up.
   */
  LOOT(Set.of("Misc"), Set.of("Mineable", "Consumable"));

  /** Every item type a group books, for {@link GameItemRepository#findStockable}. */
  @Unmodifiable
  static final Set<String> TYPES =
      Arrays.stream(values())
          .flatMap(group -> group.types.stream())
          .collect(Collectors.toUnmodifiableSet());

  /**
   * The type and sub-type keys the groups leave out, for {@link GameItemRepository#findStockable};
   * never empty, as that query requires.
   */
  @Unmodifiable
  static final Set<String> EXCLUDED_TYPE_SUB_TYPES =
      Arrays.stream(values())
          .flatMap(StockableItemGroup::excludedTypeSubTypes)
          .collect(Collectors.toUnmodifiableSet());

  /** The P4K item types of the group. */
  @Unmodifiable private final Set<String> types;

  /** The sub-types of the group's types that stay out of the Lager. */
  @Unmodifiable private final Set<String> excludedSubTypes;

  /**
   * Pairs each of the group's types with each of its excluded sub-types.
   *
   * @return the {@link GameItemRepository#typeSubTypeKey(String, String)} keys of the group
   */
  @NotNull
  private Stream<String> excludedTypeSubTypes() {
    return types.stream()
        .flatMap(type ->
            excludedSubTypes.stream()
                .map(subType -> GameItemRepository.typeSubTypeKey(type, subType)));
  }

  /**
   * Collects the item types of every ship component type except the turret, plus further types.
   *
   * @param furtherTypes item types the group books beside the component types
   * @return the item types
   */
  @NotNull
  @Unmodifiable
  private static Set<String> shipComponentTypesWithoutTurrets(@NotNull String... furtherTypes) {
    return Stream.concat(
            Arrays.stream(ShipComponentType.values())
                .filter(type -> type != ShipComponentType.TURRET)
                .map(ShipComponentType::getWikiType),
            Arrays.stream(furtherTypes))
        .collect(Collectors.toUnmodifiableSet());
  }
}
