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
 * The P4K import links a paint item to the ship types it fits in {@code ship_type_paint}, replaces
 * the links of every listed paint and removes those of unlisted paints only under a complete kind
 * (REQ-P4K-041).
 */
@SpringBootTest
@Transactional
class P4kPaintImportTest {

  /** The Avenger paint of the fixture. */
  private static final UUID PAINT = UUID.fromString("05694347-4761-446b-8305-78e8af4b911a");

  /** A real paint that fits no ship, absent from the fixture. */
  private static final UUID LONE_PAINT = UUID.fromString("07c8e30c-fa4f-4ac3-ae39-33eae6a50be7");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private ShipTypePaintRepository paintRepository;
  @Autowired private CatalogueItemReassignmentRepository reassignments;
  @Autowired private EntityManager entityManager;

  private P4kImportRuns runs;
  private ShipType titan;
  private ShipType hornet;
  private ShipType javelin;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
    titan = ship("Avenger Titan", "AEGS_Avenger_Titan");
    hornet = ship("Hornet AI", P4kTestCatalogues.AI_SHIP_CLASS);
    javelin = ship("Javelin", "AEGS_Javelin");
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
   * Stores a paint item the catalogue knows by GUID.
   *
   * @param guid the P4K GUID
   * @param name the name
   * @return the stored item
   */
  private @NotNull GameItem paint(@NotNull UUID guid, @NotNull String name) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setP4kUuid(guid);
    item.setSourceSystems(GameItemSourceSystem.BOTH);
    return gameItemRepository.save(item);
  }

  /**
   * Returns the catalogue with the fixture paint fitting the three fixture ship types and a second
   * paint fitting none.
   *
   * @return the catalogue tree
   */
  private static @NotNull ObjectNode threeShipsAndALonePaint() {
    ObjectNode root = P4kTestCatalogues.tree();
    ArrayNode paints = (ArrayNode) root.get("paints");
    ArrayNode ships = (ArrayNode) paints.get(0).get("shipGuids");
    ships.removeAll();
    ships.add(P4kTestCatalogues.AI_SHIP.toString());
    ships.add(P4kTestCatalogues.PLAYER_SHIP.toString());
    ships.add(P4kTestCatalogues.SPECIAL_SHIP.toString());
    ObjectNode lone = paints.addObject();
    lone.put("itemGuid", LONE_PAINT.toString());
    lone.putArray("shipGuids");
    return root;
  }

  /**
   * Reads the ship types a paint is linked to.
   *
   * @param paint the paint item
   * @return the ship type ids
   */
  private @NotNull List<UUID> shipsOf(@NotNull GameItem paint) {
    entityManager.flush();
    entityManager.clear();
    return paintRepository.findAll().stream()
        .filter(link -> paint.getId().equals(link.getGameItemId()))
        .map(ShipTypePaint::getShipTypeId)
        .toList();
  }

  @Test
  void aPaintFittingThreeShipTypesYieldsThreeLinksAndOneFittingNoneYieldsNone() {
    GameItem avenger = paint(PAINT, "Avenger Copernicus Paint");
    GameItem lone = paint(LONE_PAINT, "Lone Paint");

    runs.apply(P4kTestCatalogues.bytes(threeShipsAndALonePaint()), false);

    assertThat(shipsOf(avenger))
        .containsExactlyInAnyOrder(titan.getId(), hornet.getId(), javelin.getId());
    assertThat(shipsOf(lone)).isEmpty();
  }

  @Test
  void aPreviewLinksNothing() {
    paint(PAINT, "Avenger Copernicus Paint");

    runs.preview(P4kTestCatalogues.bytes(threeShipsAndALonePaint()));
    entityManager.flush();

    assertThat(paintRepository.count()).isZero();
  }

  @Test
  void aSecondApplyReplacesTheLinksOfAListedPaint() {
    GameItem avenger = paint(PAINT, "Avenger Copernicus Paint");
    runs.apply(P4kTestCatalogues.bytes(threeShipsAndALonePaint()), false);

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(shipsOf(avenger)).containsExactly(titan.getId());
  }

  @Test
  void aPartialKindKeepsTheLinksOfUnlistedPaintsAndACompleteKindRemovesThem() {
    GameItem lone = paint(LONE_PAINT, "Lone Paint");
    paintRepository.save(new ShipTypePaint(javelin.getId(), lone.getId()));
    ObjectNode partial = P4kTestCatalogues.tree();
    ((ObjectNode) partial.get("completeness").get("paints")).put("status", "partial");

    runs.apply(P4kTestCatalogues.bytes(partial), false);

    assertThat(shipsOf(lone)).containsExactly(javelin.getId());

    runs.apply(P4kTestCatalogues.fixtureBytes(), false);

    assertThat(shipsOf(lone)).isEmpty();
  }

  @Test
  void aMergedPaintItemHandsItsLinksToTheSurvivorWithoutDuplicates() {
    GameItem retired = paint(PAINT, "Avenger Copernicus Paint");
    GameItem survivor = paint(LONE_PAINT, "Survivor Paint");
    paintRepository.save(new ShipTypePaint(titan.getId(), retired.getId()));
    paintRepository.save(new ShipTypePaint(javelin.getId(), retired.getId()));
    paintRepository.save(new ShipTypePaint(javelin.getId(), survivor.getId()));
    entityManager.flush();

    int moved = reassignments.paints(retired.getId(), survivor.getId());

    assertThat(moved).isEqualTo(1);
    assertThat(shipsOf(survivor)).containsExactlyInAnyOrder(titan.getId(), javelin.getId());
  }
}
