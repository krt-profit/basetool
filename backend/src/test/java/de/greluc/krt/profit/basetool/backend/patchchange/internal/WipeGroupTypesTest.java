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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import java.util.Arrays;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The wipe groups over catalogue types (REQ-PATCH-010): the P4K type and sub-type decide, the SC
 * Wiki classification and the item kind stand in for an untyped item, and an item no group claims
 * is an other item.
 */
class WipeGroupTypesTest {

  @ParameterizedTest(name = "{0}:{1} is {2}")
  @CsvSource(
      value = {
        "Food, NULL, CONSUMABLES",
        "Drink, NULL, CONSUMABLES",
        "Bottle, NULL, CONSUMABLES",
        "FPS_Consumable, Medical, CONSUMABLES",
        "WeaponAttachment, Magazine, CONSUMABLES",
        "WeaponAttachment, Missile, CONSUMABLES",
        "WeaponPersonal, Grenade, CONSUMABLES",
        "Misc, Consumable, CONSUMABLES",
        "WeaponPersonal, Large, FPS_EQUIPMENT",
        "WeaponAttachment, IronSight, FPS_EQUIPMENT",
        "Char_Armor_Helmet, Heavy, FPS_EQUIPMENT",
        "Char_Armor_Undersuit, NULL, FPS_EQUIPMENT",
        "Char_Clothing_Torso_0, NULL, FPS_EQUIPMENT",
        "Char_Accessory_Eyes, NULL, FPS_EQUIPMENT",
        "Suit, NULL, FPS_EQUIPMENT",
        "Shield, NULL, SHIP_COMPONENTS",
        "WeaponGun, Gun, SHIP_COMPONENTS",
        "Turret, NULL, SHIP_COMPONENTS",
        "GroundVehicleMissileLauncher, NULL, SHIP_COMPONENTS",
        "Misc, NULL, OTHER_ITEMS",
        "RemovableChip, SystemAccess, OTHER_ITEMS",
        "InventoryContainer, NULL, OTHER_ITEMS",
        "Currency, NULL, OTHER_ITEMS"
      },
      nullValues = "NULL")
  void theP4kTypeAndSubTypeDecide(String type, @Nullable String subType, WipeGroup group) {
    assertThat(WipeGroupTypes.groupOf(item(type, subType, null, GameItemKind.GENERIC)))
        .isEqualTo(group);
  }

  @Test
  void aTypedItemKeepsItsTypeOverItsKind() {
    assertThat(WipeGroupTypes.groupOf(item("Paints", null, null, GameItemKind.VEHICLE_ITEM)))
        .isEqualTo(WipeGroup.OTHER_ITEMS);
  }

  @ParameterizedTest(name = "{0} / {1} is {2}")
  @CsvSource(
      value = {
        "FPS.Consumable.Food, GENERIC, CONSUMABLES",
        "FPS.WeaponAttachment.Magazine, WEAPON_ATTACHMENT, CONSUMABLES",
        "FPS.WeaponAttachment.Optics, GENERIC, FPS_EQUIPMENT",
        "FPS.Armor.Heavy, GENERIC, FPS_EQUIPMENT",
        "FPS.Clothing.Hat, GENERIC, FPS_EQUIPMENT",
        "Ship.Weapon.Gun, GENERIC, SHIP_COMPONENTS",
        "NULL, FOOD, CONSUMABLES",
        "NULL, WEAPON, FPS_EQUIPMENT",
        "NULL, ARMOR, FPS_EQUIPMENT",
        "NULL, CLOTHING, FPS_EQUIPMENT",
        "NULL, VEHICLE_ITEM, SHIP_COMPONENTS",
        "NULL, VEHICLE_WEAPON, SHIP_COMPONENTS",
        "NULL, GENERIC, OTHER_ITEMS",
        "NULL, NULL, OTHER_ITEMS"
      },
      nullValues = "NULL")
  void theWikiClassificationAndTheKindStandInForAnUntypedItem(
      @Nullable String classification, @Nullable GameItemKind kind, WipeGroup group) {
    assertThat(WipeGroupTypes.groupOf(item(null, null, classification, kind))).isEqualTo(group);
  }

  @ParameterizedTest(name = "{0} material is {1}")
  @CsvSource({"RAW, MATERIALS_RAW", "REFINED, MATERIALS_REFINED", "NO_REFINE, MATERIALS_NO_REFINE"})
  void aMaterialFallsIntoTheGroupOfItsType(MaterialType type, WipeGroup group) {
    StockWipeRow row =
        new StockWipeRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            type,
            null,
            null,
            null,
            null,
            null);

    assertThat(WipeGroupTypes.groupOf(row)).isEqualTo(group);
  }

  @Test
  void everyShipComponentTypeIsAShipComponent() {
    Arrays.stream(ShipComponentType.values())
        .forEach(component ->
            assertThat(WipeGroupTypes.groupOf(item(component.getWikiType(), null, null, null)))
                .as(component.name())
                .isEqualTo(WipeGroup.SHIP_COMPONENTS));
  }

  @ParameterizedTest
  @EnumSource(
      value = WipeGroup.class,
      names = {"OTHER_ITEMS"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyGroupButTheOtherItemsNamesItsTypes(WipeGroup group) {
    assertThat(WipeGroupTypes.typesOf(group)).isNotEmpty();
  }

  @Test
  void theOtherItemsNameNoTypeAndTheConsumablesNameTheirs() {
    assertThat(WipeGroupTypes.typesOf(WipeGroup.OTHER_ITEMS)).isEmpty();
    assertThat(WipeGroupTypes.typesOf(WipeGroup.CONSUMABLES))
        .contains(
            new WipeGroupType(WipeGroupType.Source.ITEM_TYPE, "Food"),
            new WipeGroupType(WipeGroupType.Source.ITEM_SUB_TYPE, "WeaponAttachment:Magazine"),
            new WipeGroupType(WipeGroupType.Source.ITEM_KIND, "FOOD"));
    assertThat(WipeGroupTypes.typesOf(WipeGroup.IN_GAME_SHIPS))
        .containsExactly(new WipeGroupType(WipeGroupType.Source.SHIP_ORIGIN, "IN_GAME"));
  }

  /**
   * Builds a game-item row.
   *
   * @param type the P4K type
   * @param subType the sub-type
   * @param classification the wiki classification
   * @param kind the item kind
   * @return the row
   */
  private static StockWipeRow item(
      @Nullable String type,
      @Nullable String subType,
      @Nullable String classification,
      @Nullable GameItemKind kind) {
    return new StockWipeRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        UUID.randomUUID(),
        type,
        subType,
        classification,
        kind);
  }
}
