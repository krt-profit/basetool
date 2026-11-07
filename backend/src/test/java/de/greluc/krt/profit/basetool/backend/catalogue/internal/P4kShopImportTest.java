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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemShopAvailabilityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K import stores the shops by their unique key and their known item lists as shop-to-item
 * rows, which members see on the item as "available at"; a {@code null} list contributes nothing
 * (REQ-P4K-042, REQ-P4K-044).
 */
@SpringBootTest
@Transactional
class P4kShopImportTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShopRepository shopRepository;
  @Autowired private ShopItemRepository shopItemRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ItemCatalogueDetailsService detailsService;
  @Autowired private CatalogueItemReassignmentRepository reassignments;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;
  private GameItem shield;
  private GameItem armour;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    shield = item(P4kTestCatalogues.SHIELD, "Krt AllStop");
    armour = item(P4kShopFixtures.HEAVY_ARMOUR, "Krt Heavy Arms");
  }

  /**
   * Stores an item the catalogue knows by GUID.
   *
   * @param guid the P4K GUID
   * @param name the name
   * @return the stored item
   */
  private @NotNull GameItem item(@NotNull UUID guid, @NotNull String name) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setP4kUuid(guid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return gameItemRepository.save(item);
  }

  /**
   * Reads the shops an item is available at, as a member sees them.
   *
   * @param item the item
   * @return the shops' registry names
   */
  private @NotNull List<String> availableAt(@NotNull GameItem item) {
    entityManager.flush();
    entityManager.clear();
    return detailsService.get(item.getId()).availableAt().stream()
        .map(ItemShopAvailabilityDto::registryName)
        .toList();
  }

  /**
   * Finds a stored shop by the GUID derived from its key.
   *
   * @param guid the GUID
   * @return the shop
   */
  private @NotNull Shop shop(@NotNull UUID guid) {
    return shopRepository.findAll().stream()
        .filter(s -> guid.equals(s.getP4kUuid()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void twoShopsSharingAnEntityGuidWithDifferentKeys_areTwoRows() {
    ObjectNode root = P4kTestCatalogues.tree();
    ObjectNode bar = P4kShopFixtures.shop(root, P4kShopFixtures.BAR);
    ObjectNode second = bar.deepCopy();
    String key = bar.get("key").asString();
    second.put("key", key.replace("446c6ac8", "463be294"));
    P4kShopFixtures.shops(root).add(second);

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    List<Shop> bars =
        shopRepository.findAll().stream()
            .filter(s -> P4kShopFixtures.BAR.equals(s.getRegistryName()))
            .toList();
    assertThat(bars).hasSize(2);
    assertThat(bars).extracting(Shop::getEntityGuid).containsOnly(bars.getFirst().getEntityGuid());
    assertThat(bars).extracting(Shop::getShopKey).doesNotHaveDuplicates();
    assertThat(result.shops()).isNotNull();
    assertThat(result.shops().created()).isEqualTo(5);
  }

  @Test
  void theFixtureShopsAreStoredWithTheirPlaceKindAndItemListState() {
    ObjectNode root = P4kTestCatalogues.tree();

    runs.apply(P4kTestCatalogues.bytes(root), false);

    Shop tdd = shop(P4kShopFixtures.guid(root, P4kShopFixtures.TDD));
    assertThat(tdd.getKind()).isEqualTo(P4kShopKind.COMMEX);
    assertThat(tdd.getBrand()).isEqualTo("tdd");
    assertThat(tdd.isCommodityExchange()).isTrue();
    assertThat(tdd.hasItemList()).isFalse();
    assertThat(tdd.getStarSystem()).isEqualTo("stanton");
    Shop cubby = shop(P4kShopFixtures.guid(root, P4kShopFixtures.CUBBY_BLAST));
    assertThat(cubby.hasItemList()).isTrue();
    assertThat(cubby.getKind()).isEqualTo(P4kShopKind.PERSONAL_WEAPON);
  }

  @Test
  void anItemListedByTwoShops_showsBoth_andANullListContributesNothing() {
    ObjectNode root = P4kTestCatalogues.tree();
    ((ArrayNode) P4kShopFixtures.shop(root, P4kShopFixtures.CUBBY_BLAST).get("itemGuids"))
        .add(P4kTestCatalogues.SHIELD.toString());

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(availableAt(shield))
        .containsExactlyInAnyOrder(P4kShopFixtures.CUBBY_BLAST, P4kShopFixtures.DUMPERS_DEPOT);
    assertThat(availableAt(armour)).containsExactly(P4kShopFixtures.CUBBY_BLAST);
    UUID tdd = shop(P4kShopFixtures.guid(root, P4kShopFixtures.TDD)).getId();
    assertThat(shopItemRepository.findAll()).noneMatch(link -> tdd.equals(link.getShopId()));
  }

  @Test
  void aSecondApply_replacesAShopsItemList() {
    ObjectNode root = P4kTestCatalogues.tree();
    runs.apply(P4kTestCatalogues.bytes(root), false);
    ((ArrayNode) P4kShopFixtures.shop(root, P4kShopFixtures.DUMPERS_DEPOT).get("itemGuids"))
        .removeAll();

    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(availableAt(shield)).isEmpty();
    assertThat(shop(P4kShopFixtures.guid(root, P4kShopFixtures.DUMPERS_DEPOT)).hasItemList())
        .isTrue();
  }

  @Test
  void aShopTheCompleteKindNoLongerLists_isStampedAndLeavesTheItemsAvailability() {
    ObjectNode root = P4kTestCatalogues.tree();
    runs.apply(P4kTestCatalogues.bytes(root), false);
    UUID dumpers = P4kShopFixtures.guid(root, P4kShopFixtures.DUMPERS_DEPOT);
    P4kShopFixtures.shops(root).remove(2);

    runs.apply(P4kTestCatalogues.bytes(root), false, true);

    assertThat(shop(dumpers).getP4kDeletedAt()).isNotNull();
    assertThat(availableAt(shield)).isEmpty();
  }

  @Test
  void anUnknownKind_readsAsNull_andIsCounted() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kShopFixtures.shop(root, P4kShopFixtures.TDD).put("kind", "spaceport_kiosk");

    P4kImportResultDto result = runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(shop(P4kShopFixtures.guid(root, P4kShopFixtures.TDD)).getKind()).isNull();
    assertThat(result.shops()).isNotNull();
    assertThat(result.shops().unknownValues()).isEqualTo(1);
  }

  @Test
  void anItemMerge_movesTheShopRowsOntoTheSurvivor() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    GameItem survivor = item(UUID.randomUUID(), "Krt AllStop Survivor");
    entityManager.flush();

    int moved = reassignments.shopItems(shield.getId(), survivor.getId());

    assertThat(moved).isEqualTo(1);
    assertThat(availableAt(survivor)).containsExactly(P4kShopFixtures.DUMPERS_DEPOT);
  }
}
