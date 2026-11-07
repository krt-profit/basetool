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

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the starmap objects of a test catalogue and the UEX location rows they link to
 * (REQ-P4K-034…038).
 *
 * @param starSystems the UEX star systems
 * @param planets the UEX planets
 * @param cities the UEX cities
 */
record P4kStarmapFixtures(
    @NotNull StarSystemRepository starSystems,
    @NotNull PlanetRepository planets,
    @NotNull CityRepository cities) {

  /** The UEX name of the test star system; no other test uses it. */
  static final String SYSTEM = "Krtstanton";

  /**
   * The fixture catalogue with exactly the given starmap objects.
   *
   * @param objects the objects
   * @return the catalogue tree
   */
  @NotNull
  static ObjectNode catalogue(@NotNull ObjectNode... objects) {
    ObjectNode root = P4kTestCatalogues.tree();
    ArrayNode list = root.putArray("starmapObjects");
    for (ObjectNode object : objects) {
      list.add(object);
    }
    return root;
  }

  /**
   * A placed starmap object of the test star system, not hidden.
   *
   * @param guid the GUID
   * @param type the type
   * @param name the English name
   * @param parent the parent's GUID, or {@code null}
   * @return the record
   */
  @NotNull
  static ObjectNode object(
      @NotNull UUID guid, @NotNull String type, @NotNull String name, @Nullable UUID parent) {
    ObjectNode node = P4kTestCatalogues.tree().objectNode();
    node.put("guid", guid.toString());
    node.put("className", name.replace(' ', '_'));
    node.put("recordPath", "libs/foundry/records/starmap/pu/" + name.replace(' ', '_'));
    ObjectNode localised = node.putObject("name");
    localised.put("key", "@" + name.replace(' ', '_'));
    localised.put("en", name);
    localised.put("de", name + " (DE)");
    node.put("type", type);
    if (parent == null) {
      node.putNull("parentGuid");
    } else {
      node.put("parentGuid", parent.toString());
    }
    node.put("hideInStarmap", false);
    node.put("system", SYSTEM.toLowerCase(Locale.ROOT));
    ArrayNode position = node.putArray("position");
    position.add(1.0).add(2.0).add(3.0);
    return node;
  }

  /**
   * Saves a UEX star system.
   *
   * @param name the name
   * @param live whether UEX reports it live-available
   * @return the row
   */
  @NotNull
  StarSystem starSystem(@NotNull String name, boolean live) {
    StarSystem system = new StarSystem();
    system.setName(name);
    system.setIsAvailableLive(live);
    return starSystems.save(system);
  }

  /**
   * Saves a UEX planet.
   *
   * @param name the name
   * @param system the star system's name
   * @return the row
   */
  @NotNull
  Planet planet(@NotNull String name, @NotNull String system) {
    Planet planet = new Planet();
    planet.setName(name);
    planet.setStarSystemName(system);
    return planets.save(planet);
  }

  /**
   * Saves a UEX city.
   *
   * @param name the name
   * @param system the star system's name
   * @param planet the planet's name
   * @return the row
   */
  @NotNull
  City city(@NotNull String name, @NotNull String system, @NotNull String planet) {
    City city = new City();
    city.setName(name);
    city.setStarSystemName(system);
    city.setPlanetName(planet);
    return cities.saveAndFlush(city);
  }
}
