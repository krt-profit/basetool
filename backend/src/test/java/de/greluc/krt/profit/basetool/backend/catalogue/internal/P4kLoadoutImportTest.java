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
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The P4K import stores the default loadout of every listed ship type as its port tree, replaces it
 * on the next APPLY, keeps the trees of unlisted ships under a partial kind and counts the ports it
 * could not read in the change report (REQ-P4K-040).
 */
@SpringBootTest
@Transactional
class P4kLoadoutImportTest {

  /** The gimbal mount the Titan's nose port holds by default. */
  private static final UUID GIMBAL = UUID.fromString("ea49d194-0c0b-43e1-82fb-d72f35c1af05");

  /** The gatling the gimbal holds; the fixture has no item row for it. */
  private static final UUID GATLING = UUID.fromString("df89e9ca-8a11-477c-8abd-f4837a277dbe");

  /** The cooler the Titan's left cooler port holds; the fixture has no item row for it. */
  private static final UUID BRACER = UUID.fromString("4ec3037b-4b6f-4837-b00d-cd9b4836a82a");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypeLoadoutPortRepository portRepository;
  @Autowired private CatalogueItemReassignmentRepository reassignments;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  /**
   * Stores a ship type the catalogue matches by class name.
   *
   * @param name the name
   * @param className the class name
   * @return the stored ship type
   */
  private @NotNull ShipType ship(@NotNull String name, @NotNull String className) {
    ShipType ship = new ShipType();
    ship.setName(name);
    ship.setClassName(className);
    return shipTypeRepository.save(ship);
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
   * Reads the stored ports of a ship type in sibling order.
   *
   * @param ship the ship type
   * @return its ports
   */
  private @NotNull List<ShipTypeLoadoutPort> ports(@NotNull ShipType ship) {
    entityManager.flush();
    entityManager.clear();
    return portRepository.findAll().stream()
        .filter(p -> ship.getId().equals(p.getShipTypeId()))
        .sorted(Comparator.comparingInt(ShipTypeLoadoutPort::getPosition))
        .toList();
  }

  /**
   * Finds a port by its name and parent.
   *
   * @param ports the ports
   * @param name the port name
   * @param parent the parent port, or {@code null} for a top-level port
   * @return the port
   */
  private static @NotNull ShipTypeLoadoutPort port(
      @NotNull List<ShipTypeLoadoutPort> ports,
      @NotNull String name,
      @Nullable ShipTypeLoadoutPort parent) {
    UUID parentId = parent == null ? null : parent.getId();
    return ports.stream()
        .filter(p -> name.equals(p.getName()))
        .filter(p -> parentId == null ? p.getParentId() == null : parentId.equals(p.getParentId()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Returns the fixture's loadout of a ship.
   *
   * @param root the catalogue tree
   * @param guid the ship's GUID
   * @return the loadout
   */
  private static @NotNull ObjectNode loadout(@NotNull ObjectNode root, @NotNull UUID guid) {
    for (var node : (ArrayNode) root.get("loadouts")) {
      if (guid.toString().equals(node.get("entityGuid").asString())) {
        return (ObjectNode) node;
      }
    }
    throw new IllegalArgumentException("No loadout " + guid);
  }

  @Test
  void aShipsTreeIsStoredWithNestedPortsAndDefaultItems() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    GameItem gimbal = item(GIMBAL, "Gimbal Mount S4");

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    List<ShipTypeLoadoutPort> ports = ports(titan);

    assertThat(ports).hasSize(6);
    assertThat(
            ports.stream().filter(p -> p.getParentId() == null).map(ShipTypeLoadoutPort::getName))
        .containsExactly(
            "hardpoint_paint",
            "hardpoint_cooler_left",
            "hardpoint_weapon_class2_nose",
            "hardpoint_scanner");
    ShipTypeLoadoutPort nose = port(ports, "hardpoint_weapon_class2_nose", null);
    assertThat(nose.getMinSize()).isEqualTo(4);
    assertThat(nose.getMaxSize()).isEqualTo(4);
    assertThat(nose.getTypes()).containsExactly("Turret", "WeaponGun");
    assertThat(nose.getSource()).isEqualTo(ShipLoadoutPortSource.LOADOUT);
    assertThat(nose.getItemGuid()).isEqualTo(GIMBAL);
    assertThat(nose.getGameItemId()).isEqualTo(gimbal.getId());
    ShipTypeLoadoutPort gun = port(ports, "hardpoint_class_2", nose);
    assertThat(gun.getTypes()).containsExactly("WeaponGun.Gun");
    assertThat(gun.getSource()).isEqualTo(ShipLoadoutPortSource.OVERRIDE);
    assertThat(gun.getDisplayNameKey()).isEqualTo("@itemport_slot");
    assertThat(gun.getItemGuid()).isEqualTo(GATLING);
    ShipTypeLoadoutPort barrel = port(ports, "BAR1", gun);
    assertThat(barrel.getSource()).isEqualTo(ShipLoadoutPortSource.UNSET);
    assertThat(barrel.getFlags()).containsExactly("uneditable");
    assertThat(barrel.getItemGuid()).isNull();
    assertThat(port(ports, "hardpoint_paint", null).getRequiredTags())
        .containsExactly("Paint_Avenger");
    assertThat(port(ports, "hardpoint_scanner", null).getSource())
        .isEqualTo(ShipLoadoutPortSource.VEHICLE);
  }

  @Test
  void anItemGuidWithoutAnItemRowStoresThePortWithoutAnItemReference() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    ShipTypeLoadoutPort cooler = port(ports(titan), "hardpoint_cooler_left", null);

    assertThat(cooler.getItemGuid()).isEqualTo(BRACER);
    assertThat(cooler.getGameItemId()).isNull();
    assertThat(cooler.getFlags()).containsExactly("left", "invisible", "editable");
  }

  @Test
  void itemLoadoutsAndUnmatchedShipsStoreNothing() {
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    entityManager.flush();

    assertThat(portRepository.count()).isZero();
  }

  @Test
  void aSecondApplyReplacesTheTree() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    List<UUID> first = ports(titan).stream().map(ShipTypeLoadoutPort::getId).toList();
    ObjectNode root = P4kTestCatalogues.tree();
    ArrayNode top = (ArrayNode) loadout(root, P4kTestCatalogues.PLAYER_SHIP).get("ports");
    top.remove(3);

    runs.apply(P4kTestCatalogues.bytes(root), false);
    List<ShipTypeLoadoutPort> second = ports(titan);

    assertThat(second).hasSize(5);
    assertThat(second).extracting(ShipTypeLoadoutPort::getName).doesNotContain("hardpoint_scanner");
    assertThat(second).extracting(ShipTypeLoadoutPort::getId).doesNotContainAnyElementsOf(first);
  }

  @Test
  void aPartialKindKeepsTheTreesOfUnlistedShipsAndACompleteKindRemovesThem() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    ShipType other = ship("Unlisted Ship", "TEST_Unlisted_Ship");
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    ShipTypeLoadoutPort kept = new ShipTypeLoadoutPort();
    kept.setId(UUID.randomUUID());
    kept.setShipTypeId(other.getId());
    kept.setPosition(0);
    kept.setName("hardpoint_kept");
    kept.setTypes(List.of());
    kept.setFlags(List.of());
    kept.setRequiredTags(List.of());
    entityManager.persist(kept);
    ObjectNode partial = P4kTestCatalogues.tree();
    ((ObjectNode) partial.get("completeness").get("loadouts")).put("status", "partial");

    runs.apply(P4kTestCatalogues.bytes(partial), false);

    assertThat(ports(other))
        .extracting(ShipTypeLoadoutPort::getName)
        .containsExactly("hardpoint_kept");
    assertThat(ports(titan)).hasSize(6);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(ports(other)).isEmpty();
    assertThat(ports(titan)).hasSize(6);
  }

  @Test
  void aLoadoutWithIssuesAndUnresolvedPortsIsCountedInTheReport() {
    ship("Javelin", "AEGS_Javelin");

    List<P4kImportChange> changes =
        runs.previewWithReport(P4kTestCatalogues.fixtureBytes()).changes();

    assertThat(changes)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.LOADOUT_ISSUES)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.getGuid()).isEqualTo(P4kTestCatalogues.SPECIAL_SHIP);
          assertThat(c.getOldValue()).isEqualTo("unknown_class");
          assertThat(c.getNewValue()).isEqualTo("1");
        });
    assertThat(portRepository.count()).isZero();
  }

  @Test
  void anUnknownPortSourceIsStoredAsNullAndCounted() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    ObjectNode root = P4kTestCatalogues.tree();
    ((ObjectNode) loadout(root, P4kTestCatalogues.PLAYER_SHIP).get("ports").get(0))
        .put("source", "inherited");

    List<P4kImportChange> changes = runs.previewWithReport(P4kTestCatalogues.bytes(root)).changes();
    runs.apply(P4kTestCatalogues.bytes(root), false);

    assertThat(changes)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNKNOWN_VALUE)
        .filteredOn(c -> P4kTestCatalogues.PLAYER_SHIP.equals(c.getGuid()))
        .singleElement()
        .satisfies(c -> assertThat(c.getNewValue()).isEqualTo("inherited"));
    assertThat(port(ports(titan), "hardpoint_paint", null).getSource()).isNull();
  }

  @Test
  void aMergedItemKeepsTheDefaultItemOfItsPorts() {
    ShipType titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    GameItem retired = item(GIMBAL, "Gimbal Mount S4");
    GameItem survivor = item(UUID.randomUUID(), "Gimbal Mount S4 (Wiki)");
    runs.apply(P4kTestCatalogues.fixtureBytes(), false);
    entityManager.flush();

    reassignments.loadoutPorts(retired.getId(), survivor.getId());

    assertThat(port(ports(titan), "hardpoint_weapon_class2_nose", null).getGameItemId())
        .isEqualTo(survivor.getId());
  }
}
