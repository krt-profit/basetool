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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.blueprint.internal.BlueprintProductDto;
import de.greluc.krt.profit.basetool.backend.blueprint.internal.BlueprintProductService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RetiredGameItemException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SelectableGameItems;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockItemCatalogue;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * An admin hides a game item as not obtainable and offers it again, against PostgreSQL
 * (REQ-P4K-048): the item leaves the Lager, item-order and blueprint-product pickers, a new write
 * that names it is refused like a retired item, each change is audited once, and a change is
 * refused while a sync or an import holds the catalogue.
 */
@SpringBootTest
@Transactional
class GameItemAdminServiceTest {

  private static final String TAG = "Obtainable" + UUID.randomUUID().toString().substring(0, 8);

  private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private GameItemAdminService service;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private StockItemCatalogue stockItemCatalogue;
  @Autowired private SelectableGameItems selectableGameItems;
  @Autowired private BlueprintProductService blueprintProductService;
  @Autowired private AuditService auditService;
  @Autowired private SyncCoordinator syncCoordinator;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void aHiddenItemLeavesTheLagerPicker_isRefusedOnWrite_andComesBackWhenOffered() {
    GameItem railgun = item("Railgun", "WeaponPersonal", "Large");
    item("Rifle", "WeaponPersonal", "Large");

    GameItemAdminDto hidden = service.setObtainable(railgun.getId(), false);

    assertThat(hidden.unobtainableSince()).isNotNull();
    assertThat(hidden.retired()).isFalse();
    assertThat(bookable()).containsExactly(name("Rifle"));
    assertThatThrownBy(() -> selectableGameItems.require(railgun.getId()))
        .isInstanceOf(RetiredGameItemException.class);

    GameItemAdminDto offered = service.setObtainable(railgun.getId(), true);

    assertThat(offered.unobtainableSince()).isNull();
    assertThat(bookable()).containsExactlyInAnyOrder(name("Railgun"), name("Rifle"));
    assertThat(selectableGameItems.require(railgun.getId()).getId()).isEqualTo(railgun.getId());
  }

  @Test
  void aHiddenItemLeavesTheItemOrderPickerAndTheBlueprintProductSearch() {
    GameItem cannon = item("Cannon", "WeaponGun", "Gun");
    craftedFromOre(cannon);
    assertThat(orderable()).containsExactly(name("Cannon"));
    assertThat(products()).containsExactly(name("Cannon"));

    service.setObtainable(cannon.getId(), false);

    assertThat(orderable()).isEmpty();
    assertThat(products()).isEmpty();
    assertThat(blueprintProductService.resolveByGameItem(cannon.getId()))
        .as("an existing reference still resolves the product")
        .isPresent();
  }

  @Test
  void theItemOrderWriteFactsMarkAHiddenItemAsNotSelectable() {
    GameItem cannon = item("Order Cannon", "WeaponGun", "Gun");
    assertThat(blueprintRepository.findItemOrderDirectItems(Set.of(cannon.getId())))
        .singleElement()
        .satisfies(row -> assertThat(row.retired()).isFalse());

    service.setObtainable(cannon.getId(), false);

    assertThat(blueprintRepository.findItemOrderDirectItems(Set.of(cannon.getId())))
        .singleElement()
        .satisfies(row -> assertThat(row.retired()).as("an order line may not take it").isTrue());
  }

  @Test
  void eachChangeIsAuditedOnce_andAnUnchangedStateRecordsNothing() {
    GameItem helmet = item("Helmet", "Char_Armor_Helmet", "Heavy");

    Instant first = service.setObtainable(helmet.getId(), false).unobtainableSince();
    Instant repeated = service.setObtainable(helmet.getId(), false).unobtainableSince();
    service.setObtainable(helmet.getId(), true);
    service.setObtainable(helmet.getId(), true);

    assertThat(repeated).as("a repeated hide keeps the first time").isEqualTo(first);
    assertThat(events(helmet))
        .extracting(AuditEventDto::details)
        .containsExactlyInAnyOrder("obtainable=false", "obtainable=true");
    assertThat(events(helmet))
        .allSatisfy(event -> assertThat(event.subjectLabel()).isEqualTo(name("Helmet")));
  }

  @Test
  void aChangeWhileASyncHoldsTheLock_isAConflict_andWritesNothing() throws Exception {
    GameItem helmet = item("Locked Helmet", "Char_Armor_Helmet", "Heavy");
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread sync =
        Thread.ofVirtual()
            .start(() ->
                syncCoordinator.runExclusively(
                    "UEX",
                    () -> {
                      held.countDown();
                      awaitQuietly(release);
                    }));
    try {
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> service.setObtainable(helmet.getId(), false))
          .isInstanceOf(BusinessConflictException.class);
    } finally {
      release.countDown();
      sync.join();
    }
    assertThat(helmet.getUnobtainableSince()).isNull();
    assertThat(events(helmet)).isEmpty();
  }

  @Test
  void anUnknownItemIsNotFound() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> service.setObtainable(unknown, false))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theListShowsTheListedItems_andTheHiddenOnesWithTheRetiredAmongThem() {
    item("Alpha", "WeaponPersonal", "Large");
    GameItem bravo = item("Bravo", "WeaponPersonal", "Large");
    bravo.setUnobtainableSince(EARLIER);
    GameItem charlie = item("Charlie", "WeaponPersonal", "Large");
    charlie.setUnobtainableSince(EARLIER);
    charlie.setP4kUuid(UUID.randomUUID());
    charlie.setP4kDeletedAt(EARLIER);
    GameItem delta = item("Delta", "WeaponPersonal", "Large");
    delta.setP4kUuid(UUID.randomUUID());
    delta.setP4kHiddenAt(EARLIER);
    gameItemRepository.flush();

    assertThat(listed(false))
        .extracting(GameItemAdminDto::name)
        .containsExactly(name("Alpha"), name("Bravo"));
    assertThat(listed(true))
        .extracting(GameItemAdminDto::name, GameItemAdminDto::retired)
        .containsExactly(tuple(name("Bravo"), false), tuple(name("Charlie"), true));
    assertThat(gameItemRepository.findUnobtainableIds()).contains(bravo.getId(), charlie.getId());
    assertThat(gameItemRepository.countByUnobtainableSinceIsNotNull()).isGreaterThanOrEqualTo(2);
  }

  /**
   * Waits for a latch, keeping the thread's interrupt flag.
   *
   * @param latch the latch
   */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Reads the names the Lager item picker offers for the test's tag.
   *
   * @return the offered names
   */
  private List<String> bookable() {
    return stockItemCatalogue.findBookableItems(TAG, PageRequest.of(0, 50)).getContent().stream()
        .map(InventoryGameItemReferenceDto::name)
        .toList();
  }

  /**
   * Reads the names the item-order picker offers for the test's tag.
   *
   * @return the offered names
   */
  private List<String> orderable() {
    return stockItemCatalogue.findOrderableItems(TAG, PageRequest.of(0, 50)).getContent().stream()
        .map(InventoryGameItemReferenceDto::name)
        .toList();
  }

  /**
   * Reads the names the blueprint-product search offers for the test's tag.
   *
   * @return the offered product names
   */
  private List<String> products() {
    return blueprintProductService.searchProducts(TAG, 25, UUID.randomUUID()).stream()
        .map(BlueprintProductDto::name)
        .toList();
  }

  /**
   * Pages the admin list for the test's tag, by name.
   *
   * @param unobtainableOnly whether only the hidden items are listed
   * @return the rows
   */
  private List<GameItemAdminDto> listed(boolean unobtainableOnly) {
    return service
        .items(TAG, unobtainableOnly, PageRequest.of(0, 50, Sort.by("name", "id")))
        .getContent();
  }

  /**
   * Reads the obtainability events of one item.
   *
   * @param item the item
   * @return its events
   */
  private List<AuditEventDto> events(GameItem item) {
    return auditService
        .getEvents(
            AuditDomain.CATALOGUE,
            null,
            null,
            null,
            AuditEventType.CATALOGUE_GAME_ITEM_OBTAINABILITY_CHANGED,
            null,
            PageRequest.of(0, 50))
        .getContent()
        .stream()
        .filter(event -> item.getId().equals(event.subjectId()))
        .toList();
  }

  /**
   * Saves a game item under the test's tag.
   *
   * @param suffix the name after the tag
   * @param type the P4K item type
   * @param subType the P4K sub-type
   * @return the saved item
   */
  private GameItem item(String suffix, String type, String subType) {
    GameItem item = new GameItem();
    item.setName(name(suffix));
    item.setWikiType(type);
    item.setWikiSubType(subType);
    return gameItemRepository.saveAndFlush(item);
  }

  /**
   * Saves an active recipe that produces the item from one resolved ore, which makes the item
   * orderable.
   *
   * @param item the produced item
   */
  private void craftedFromOre(GameItem item) {
    UUID ore = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        ore,
        name("Ore " + ore));
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setOutputName(item.getName());
    blueprint.setOutputItem(item);
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setMaterial(materialRepository.getReferenceById(ore));
    ingredient.setOrderIndex(0);
    blueprint.addIngredient(ingredient);
    blueprintRepository.saveAndFlush(blueprint);
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
