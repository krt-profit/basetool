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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the Lager's stocked item groups (REQ-INV-029): which P4K item types they book, and which
 * sub-types of a booked type stay out as consumables or materials.
 */
class StockableItemGroupTest {

  @ParameterizedTest(name = "{0} is booked")
  @ValueSource(
      strings = {
        "WeaponPersonal",
        "WeaponAttachment",
        "Char_Armor_Helmet",
        "Char_Armor_Torso",
        "Char_Armor_Arms",
        "Char_Armor_Legs",
        "Char_Armor_Backpack",
        "Char_Armor_Undersuit",
        "Suit",
        "GroundVehicleMissileLauncher",
        "MiningModifier",
        "Gadget",
        "SalvageModifier",
        "InventoryContainer",
        "Misc"
      })
  void theGroupsBookTheGearAndLootTypes(String type) {
    assertThat(StockableItemGroup.TYPES).contains(type);
  }

  @ParameterizedTest(name = "{0} is not booked")
  @ValueSource(
      strings = {
        "Turret",
        "Char_Clothing_Torso_0",
        "Char_Accessory_Eyes",
        "MobiGlas",
        "Paints",
        "Flair_Surface",
        "FPS_Consumable",
        "Food",
        "Drink",
        "Bottle",
        "Grenade",
        "RemovableChip",
        "Currency",
        "Cargo",
        "Seat",
        "FuelTank",
        "WeaponDefensive",
        "Armor"
      })
  void clothingDecorationConsumablesAndFixedShipPartsAreNotBooked(String type) {
    assertThat(StockableItemGroup.TYPES).doesNotContain(type);
  }

  @Test
  void everyShipComponentTypeButTheTurretIsBooked() {
    Arrays.stream(ShipComponentType.values())
        .forEach(component ->
            assertThat(StockableItemGroup.TYPES.contains(component.getWikiType()))
                .as(component.name())
                .isEqualTo(component != ShipComponentType.TURRET));
  }

  @ParameterizedTest(name = "{0}:{1} books {2}")
  @CsvSource(
      value = {
        "WeaponPersonal, Large, true",
        "Misc, NULL, true",
        "Char_Armor_Helmet, Heavy, true",
        "WeaponPersonal, Grenade, false",
        "Misc, Mineable, false",
        "Paints, UNDEFINED, false",
        "NULL, NULL, false"
      },
      nullValues = "NULL")
  void oneItemIsBookedByItsTypeUnlessItsSubTypeIsLeftOut(
      String type, String subType, boolean booked) {
    assertThat(StockableItemGroup.covers(type, subType)).isEqualTo(booked);
  }

  @Test
  void ammunitionGrenadesMineableGemsAndFoodStayOutOfTheirTypes() {
    assertThat(StockableItemGroup.EXCLUDED_TYPE_SUB_TYPES)
        .containsExactlyInAnyOrder(
            "WeaponPersonal:Grenade",
            "WeaponAttachment:Magazine",
            "WeaponAttachment:Missile",
            "Misc:Mineable",
            "Misc:Consumable");
  }
}
