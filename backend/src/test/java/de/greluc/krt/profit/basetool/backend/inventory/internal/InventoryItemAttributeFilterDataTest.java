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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies against PostgreSQL that the category, component-type, size and grade filters narrow the
 * Lager item queries (REQ-INV-063). Each test rolls back.
 */
@SpringBootTest
@Transactional
class InventoryItemAttributeFilterDataTest {

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private InventoryItemViewRepository inventoryItemViewRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private SquadronRepository squadronRepository;

  @PersistenceContext private EntityManager entityManager;

  private User user;
  private GameItem quantumDriveS2A;
  private GameItem quantumDriveS1C;
  private GameItem shieldS2A;
  private GameItem paintGradeA;
  private GameItem rifle;

  @BeforeEach
  void stockOneOfEach() {
    user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    userRepository.save(user);

    Location location = new Location();
    location.setName("Hub-" + UUID.randomUUID());
    locationRepository.save(location);

    quantumDriveS2A = gameItem(GameItemKind.VEHICLE_ITEM, "QuantumDrive", 2, "A");
    quantumDriveS1C = gameItem(GameItemKind.VEHICLE_ITEM, "QuantumDrive", 1, " C ");
    shieldS2A = gameItem(GameItemKind.VEHICLE_ITEM, "Shield", 2, "A");
    paintGradeA = gameItem(GameItemKind.VEHICLE_ITEM, "Paints", 2, "A");
    rifle = gameItem(GameItemKind.WEAPON, "WeaponPersonal", null, null);
    quantumDriveS2A.setItemClass(ShipComponentClass.MILITARY);
    shieldS2A.setItemClass(ShipComponentClass.CIVILIAN);
    paintGradeA.setItemClass(ShipComponentClass.MILITARY);
    for (GameItem gameItem :
        List.of(quantumDriveS2A, quantumDriveS1C, shieldS2A, paintGradeA, rifle)) {
      InventoryItem row = new InventoryItem();
      row.setUser(user);
      row.setLocation(location);
      row.setGameItem(gameItem);
      row.setAmount(1.0);
      row.setPersonal(false);
      row.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
      inventoryItemRepository.save(row);
    }
    entityManager.flush();
  }

  @Test
  void noAttributeFilter_listsEveryItem() {
    assertThat(userStacks(ItemAttributeFilter.NONE))
        .containsExactlyInAnyOrder(quantumDriveS2A, quantumDriveS1C, shieldS2A, paintGradeA, rifle);
  }

  @Test
  void kind_narrowsToTheCategory() {
    assertThat(
            userStacks(
                new ItemAttributeFilter(List.of(GameItemKind.WEAPON), null, null, null, null)))
        .containsExactly(rifle);
  }

  @Test
  void componentTypeAndSize_narrowToBoth() {
    assertThat(
            userStacks(
                new ItemAttributeFilter(
                    null, List.of(ShipComponentType.QUANTUM_DRIVE), List.of(2), null, null)))
        .containsExactly(quantumDriveS2A);
  }

  @Test
  void gradeAlone_skipsNonComponentsCarryingTheGrade() {
    assertThat(userStacks(new ItemAttributeFilter(null, null, null, List.of("A"), null)))
        .as("a paint carries grade A but is no component, so the grade filter skips it")
        .containsExactlyInAnyOrder(quantumDriveS2A, shieldS2A);
  }

  @Test
  void grade_matchesAStoredGradeWithSurroundingWhitespace() {
    assertThat(userStacks(new ItemAttributeFilter(null, null, null, List.of("C"), null)))
        .containsExactly(quantumDriveS1C);
  }

  @Test
  void itemClass_narrowsToComponentsOfTheClass() {
    assertThat(
            userStacks(
                new ItemAttributeFilter(
                    null, null, null, null, List.of(ShipComponentClass.MILITARY))))
        .as("an item without a class and a non-component carrying the class are both skipped")
        .containsExactly(quantumDriveS2A);
  }

  @Test
  void itemClass_combinesWithComponentTypeAndSize() {
    assertThat(
            userStacks(
                new ItemAttributeFilter(
                    null,
                    List.of(ShipComponentType.QUANTUM_DRIVE, ShipComponentType.SHIELD),
                    List.of(2),
                    null,
                    List.of(ShipComponentClass.CIVILIAN))))
        .containsExactly(shieldS2A);
  }

  @Test
  void globalStacks_applyTheSameClauses() {
    ItemAttributeFilter filter =
        new ItemAttributeFilter(null, List.of(ShipComponentType.SHIELD), null, null, null);

    List<GameItem> items =
        inventoryItemViewRepository
            .findGlobalItemStacks(
                false,
                null,
                false,
                null,
                false,
                null,
                true,
                null,
                Set.of(),
                false,
                false,
                filter.hasKinds(),
                filter.kindsOrNull(),
                filter.hasWikiTypes(),
                filter.wikiTypesOrNull(),
                filter.hasSizes(),
                filter.sizesOrNull(),
                filter.hasGrades(),
                filter.gradesOrNull(),
                filter.hasItemClasses(),
                filter.itemClassesOrNull())
            .stream()
            .map(InventoryItemStackAggregate::gameItem)
            .filter(this::isFixture)
            .toList();

    assertThat(items).containsExactly(shieldS2A);
  }

  @Test
  void entryIds_applyTheSameClauses() {
    ItemAttributeFilter filter = new ItemAttributeFilter(null, null, List.of(1), null, null);

    List<UUID> ids =
        inventoryItemViewRepository.findUserItemEntryIds(
            user.getId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            false,
            false,
            false,
            filter.hasKinds(),
            filter.kindsOrNull(),
            filter.hasWikiTypes(),
            filter.wikiTypesOrNull(),
            filter.hasSizes(),
            filter.sizesOrNull(),
            filter.hasGrades(),
            filter.gradesOrNull(),
            filter.hasItemClasses(),
            filter.itemClassesOrNull());

    assertThat(ids).hasSize(1);
    assertThat(inventoryItemRepository.findById(ids.get(0)).orElseThrow().getGameItem())
        .isEqualTo(quantumDriveS1C);
  }

  private List<GameItem> userStacks(ItemAttributeFilter filter) {
    return inventoryItemViewRepository
        .findUserItemStacks(
            user.getId(),
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            false,
            false,
            false,
            filter.hasKinds(),
            filter.kindsOrNull(),
            filter.hasWikiTypes(),
            filter.wikiTypesOrNull(),
            filter.hasSizes(),
            filter.sizesOrNull(),
            filter.hasGrades(),
            filter.gradesOrNull(),
            filter.hasItemClasses(),
            filter.itemClassesOrNull())
        .stream()
        .map(InventoryItemStackAggregate::gameItem)
        .toList();
  }

  private boolean isFixture(GameItem gameItem) {
    return List.of(quantumDriveS2A, quantumDriveS1C, shieldS2A, paintGradeA, rifle).stream()
        .anyMatch(fixture -> fixture.getId().equals(gameItem.getId()));
  }

  private GameItem gameItem(GameItemKind kind, String wikiType, Integer size, String grade) {
    GameItem gameItem = new GameItem();
    gameItem.setName(wikiType + "-" + UUID.randomUUID());
    gameItem.setKind(kind);
    gameItem.setWikiType(wikiType);
    gameItem.setSizeClass(size);
    gameItem.setGrade(grade);
    return gameItemRepository.save(gameItem);
  }
}
