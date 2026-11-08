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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalogues and rows the P4K sweep tests share: a kind's records replaced by copies of one fixture
 * record under chosen GUIDs, and P4K-known rows holding GUIDs, so a test controls exactly which
 * rows the P4K knows and which the catalogue lists.
 *
 * @param items the item repository
 * @param ships the ship-type repository
 */
record P4kSweepFixtures(@NotNull GameItemRepository items, @NotNull ShipTypeRepository ships) {

  /**
   * Makes fresh GUIDs.
   *
   * @param count how many
   * @return the GUIDs
   */
  @NotNull
  static List<UUID> guids(int count) {
    return IntStream.range(0, count).mapToObj(_ -> UUID.randomUUID()).toList();
  }

  /**
   * Replaces a kind's records with copies of one fixture record, one per GUID, each under a class
   * name and a name of its own.
   *
   * @param root the catalogue tree
   * @param kindField the kind's field, e.g. {@code items}
   * @param template the GUID of the fixture record to copy
   * @param listed the GUIDs the kind lists
   */
  static void listOnly(
      @NotNull ObjectNode root,
      @NotNull String kindField,
      @NotNull UUID template,
      @NotNull List<UUID> listed) {
    ObjectNode original = P4kTestCatalogues.record(root, kindField, template).deepCopy();
    ArrayNode records = root.putArray(kindField);
    for (UUID guid : listed) {
      ObjectNode copy = original.deepCopy();
      copy.put("guid", guid.toString());
      copy.put("className", "TEST_SWEEP_" + guid);
      ((ObjectNode) copy.get("name")).put("en", "Sweep " + guid);
      records.add(copy);
    }
  }

  /**
   * Saves items the P4K knows under the given GUIDs.
   *
   * @param guids the GUIDs
   * @return the saved items
   */
  @NotNull
  List<GameItem> knownItems(@NotNull List<UUID> guids) {
    return guids.stream().map(this::knownItem).toList();
  }

  /**
   * Saves an item the P4K knows.
   *
   * @param guid its GUID
   * @return the saved item
   */
  @NotNull
  GameItem knownItem(@NotNull UUID guid) {
    GameItem item = new GameItem();
    item.setName("Known item " + guid);
    item.setClassName("TEST_SWEEP_" + guid);
    item.setP4kUuid(guid);
    return items.save(item);
  }

  /**
   * Saves ships the P4K knows under the given GUIDs.
   *
   * @param guids the GUIDs
   * @return the saved ships
   */
  @NotNull
  List<ShipType> knownShips(@NotNull List<UUID> guids) {
    return guids.stream().map(this::knownShip).toList();
  }

  /**
   * Saves a ship the P4K knows.
   *
   * @param guid its GUID
   * @return the saved ship
   */
  @NotNull
  ShipType knownShip(@NotNull UUID guid) {
    ShipType ship = new ShipType();
    ship.setName("Known ship " + guid);
    ship.setClassName("TEST_SWEEP_" + guid);
    ship.setP4kUuid(guid);
    return ships.save(ship);
  }
}
