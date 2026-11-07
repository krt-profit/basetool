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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CityRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gives a test location the place every {@code location} row names (REQ-P4K-036, {@code
 * ck_location_place}): a city of its own without a UEX id, so no exchange reference, no refinery
 * and no starmap link come with it.
 */
public final class TestPlaces {

  /** Non-instantiable holder of static helpers. */
  private TestPlaces() {}

  /**
   * Persists a city for a location and points the location at it.
   *
   * @param location the unsaved location
   * @param cities the test's city repository
   * @return the same location
   */
  @NotNull
  public static Location onOwnCity(@NotNull Location location, @NotNull CityRepository cities) {
    City city = new City();
    city.setName("Test city " + UUID.randomUUID());
    location.setCity(cities.save(city));
    return location;
  }

  /**
   * Inserts a visible location on a city of its own, for a test that writes its rows with SQL.
   *
   * @param jdbc the test's JDBC template
   * @param id the location's id
   * @param name the location's name
   */
  public static void insertLocation(
      @NotNull JdbcTemplate jdbc, @NotNull UUID id, @NotNull String name) {
    UUID city = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO city (id, version, created_at, updated_at, name) VALUES (?, 0, now(), now(),"
            + " ?)",
        city,
        "Test city of " + name);
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id) VALUES (?, ?, false, ?)", id, name, city);
  }

  /**
   * Inserts a starmap object an admin set visible, for a test that creates a storage place on it
   * (REQ-P4K-036, REQ-P4K-038).
   *
   * @param jdbc the test's JDBC template
   * @return the object's id, for {@code location.starmap_object_id}
   */
  @NotNull
  public static UUID visibleNode(@NotNull JdbcTemplate jdbc) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO starmap_object (id, p4k_uuid, class_name, visibility_override, version)"
            + " VALUES (?, ?, ?, 'VISIBLE', 0)",
        id,
        UUID.randomUUID(),
        "Test_Node_" + id);
    return id;
  }
}
