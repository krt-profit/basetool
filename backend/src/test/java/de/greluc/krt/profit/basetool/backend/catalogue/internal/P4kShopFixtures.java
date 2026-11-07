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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShopDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shops of the trimmed LIVE fixture (REQ-P4K-042…044) and ways to vary them. The fixture holds
 * four real shops: a Pyro rest-stop bar without a place or an item list, Cubby Blast and Dumper's
 * Depot of Area 18 with their item lists cut to the fixture's items, and the TDD commodity exchange
 * of New Babbage without an item list.
 */
final class P4kShopFixtures {

  /** Cubby Blast's registry name; its list names the two fixture armours. */
  static final String CUBBY_BLAST = "CubbyBlast_Area18";

  /** Dumper's Depot's registry name; its list names the fixture shield. */
  static final String DUMPERS_DEPOT = "DumpersDepot_Area18";

  /** The TDD exchange's registry name; its item list is unknown. */
  static final String TDD = "L_TDD_NewBabbage";

  /** The Pyro bar's registry name; it has no place. */
  static final String BAR = "RestStop_Bars";

  /** A heavy armour the Cubby Blast list names. */
  static final UUID HEAVY_ARMOUR = UUID.fromString("bef9cd69-24f0-434a-ba7a-2bf751228093");

  /** Non-instantiable holder of static helpers. */
  private P4kShopFixtures() {}

  /**
   * Returns the shops array of a tree.
   *
   * @param root the root
   * @return {@code shops}
   */
  @NotNull
  static ArrayNode shops(@NotNull ObjectNode root) {
    return (ArrayNode) root.get("shops");
  }

  /**
   * Finds a shop of a tree by its registry name.
   *
   * @param root the root
   * @param registryName the registry name
   * @return the shop record
   */
  @NotNull
  static ObjectNode shop(@NotNull ObjectNode root, @NotNull String registryName) {
    for (JsonNode node : shops(root)) {
      if (registryName.equals(node.get("registryName").asString())) {
        return (ObjectNode) node;
      }
    }
    throw new IllegalArgumentException("No shop " + registryName);
  }

  /**
   * Returns the GUID the import keys a shop of a tree by.
   *
   * @param root the root
   * @param registryName the registry name
   * @return the name-based UUID of its key
   */
  @NotNull
  static UUID guid(@NotNull ObjectNode root, @NotNull String registryName) {
    return P4kShopDto.guidOf(shop(root, registryName).get("key").asString());
  }

  /**
   * Builds a shop record at a starmap object, with an unknown item list.
   *
   * @param key the key
   * @param registryName the registry name
   * @param place the starmap object's GUID, or {@code null}
   * @return the record
   */
  @NotNull
  static ObjectNode shop(@NotNull String key, @NotNull String registryName, @Nullable UUID place) {
    ObjectNode node = P4kTestCatalogues.tree().objectNode();
    node.put("key", key);
    node.put("entityGuid", key.substring(key.lastIndexOf(',') + 1));
    node.put("system", "krtstanton");
    node.put("entityName", registryName);
    node.put("registryName", registryName);
    node.putNull("defaultValue");
    node.putArray("containers").add("OOC_Krtstanton");
    node.putNull("body");
    node.putNull("location");
    if (place == null) {
      node.putNull("starmapObjectGuid");
    } else {
      node.put("starmapObjectGuid", place.toString());
    }
    node.put("kind", "personalweapon");
    node.putNull("brand");
    node.put("commodityExchange", false);
    node.putNull("inventory");
    node.putNull("itemGuids");
    return node;
  }
}
