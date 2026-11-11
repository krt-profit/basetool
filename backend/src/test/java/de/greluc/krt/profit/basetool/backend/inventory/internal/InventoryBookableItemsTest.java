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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockItemCatalogue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the Lager item picker read against PostgreSQL (REQ-INV-029): every listed blueprint output
 * and every listed item of a stocked type is bookable; consumables, clothing, paints, turrets,
 * untyped rows and retired rows are not.
 */
@SpringBootTest
@Transactional
class InventoryBookableItemsTest {

  private static final String TAG = "Bookable" + UUID.randomUUID().toString().substring(0, 8);

  private static final Instant RETIRED = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private StockItemCatalogue catalogue;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;

  @Test
  void stockedTypesAndBlueprintOutputsAreBookable_consumablesAndOtherTypesAreNot() {
    item("Railgun", "WeaponPersonal", "Large");
    item("Grenade", "WeaponPersonal", "Grenade");
    item("Magazine", "WeaponAttachment", "Magazine");
    crafted(item("Crafted Magazine", "WeaponAttachment", "Magazine"));
    item("Scope", "WeaponAttachment", "IronSight");
    item("Helmet", "Char_Armor_Helmet", "Heavy");
    item("Shield", "Shield", "UNDEFINED");
    item("Remote Turret", "Turret", "GunTurret");
    item("Artifact", "Misc", "UNDEFINED");
    item("Medal", "Misc", null);
    item("Gem", "Misc", "Mineable");
    item("MedPen", "FPS_Consumable", "Medical");
    item("Shirt", "Char_Clothing_Torso_0", "UNDEFINED");
    item("Livery", "Paints", "UNDEFINED");
    item("Untyped", null, null);
    crafted(item("Untyped Crafted", null, null));
    gameItemRepository.flush();

    assertThat(bookable(TAG))
        .containsExactlyInAnyOrder(
            name("Railgun"),
            name("Crafted Magazine"),
            name("Scope"),
            name("Helmet"),
            name("Shield"),
            name("Artifact"),
            name("Medal"),
            name("Untyped Crafted"));
  }

  @Test
  void aRetiredOrHiddenItemAndAnOutputOfARetiredRecipeAreNotBookable() {
    item("Live Railgun", "WeaponPersonal", "Large");
    GameItem swept = item("Swept Railgun", "WeaponPersonal", "Large");
    swept.setP4kUuid(UUID.randomUUID());
    swept.setP4kDeletedAt(RETIRED);
    GameItem hidden = item("Hidden Helmet", "Char_Armor_Helmet", "Heavy");
    hidden.setP4kUuid(UUID.randomUUID());
    hidden.setP4kHiddenAt(RETIRED);
    crafted(item("Seat", "Seat", "UNDEFINED")).setP4kDeletedAt(RETIRED);
    gameItemRepository.flush();
    blueprintRepository.flush();

    assertThat(bookable(TAG)).containsExactly(name("Live Railgun"));
  }

  @Test
  void theSearchMatchesPercentAndUnderscoreLiterally() {
    item("100% Railgun", "WeaponPersonal", "Large");
    item("_Railgun", "WeaponPersonal", "Large");
    item("X100x Railgunq", "WeaponPersonal", "Large");
    gameItemRepository.flush();

    assertThat(bookable("100% R")).containsExactly(name("100% Railgun"));
    assertThat(bookable(TAG + " _")).containsExactly(name("_Railgun"));
  }

  /**
   * Reads the names the Lager item picker offers for a search term.
   *
   * @param search the raw search term
   * @return the offered names
   */
  private List<String> bookable(String search) {
    return catalogue.findBookableItems(search, PageRequest.of(0, 100)).getContent().stream()
        .map(InventoryGameItemReferenceDto::name)
        .toList();
  }

  /**
   * Saves a game item under the test's tag.
   *
   * @param suffix the name after the tag
   * @param type the P4K item type, or {@code null}
   * @param subType the P4K sub-type, or {@code null}
   * @return the saved item
   */
  private GameItem item(String suffix, @Nullable String type, @Nullable String subType) {
    GameItem item = new GameItem();
    item.setName(name(suffix));
    item.setWikiType(type);
    item.setWikiSubType(subType);
    return gameItemRepository.save(item);
  }

  /**
   * Saves an active recipe that produces the item.
   *
   * @param item the produced item
   * @return the recipe
   */
  private Blueprint crafted(GameItem item) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setOutputName(item.getName());
    blueprint.setOutputItem(item);
    return blueprintRepository.save(blueprint);
  }

  /**
   * Prefixes a name with the test's tag.
   *
   * @param suffix the name after the tag
   * @return the tagged name
   */
  private static String name(String suffix) {
    return TAG + " " + suffix;
  }
}
