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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static de.greluc.krt.profit.basetool.backend.support.StatementCounter.measured;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLocationLinks.Links;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies against PostgreSQL that the exchange reads the UEX places and starmap GUIDs of a
 * relocation preview's places in one statement, however many places it names (REQ-PATCH-051,
 * REQ-DATA-003).
 */
@SpringBootTest
class ExchangeLocationLinksNoNPlusOneTest {

  /** Places of each kind: on a UEX city and on a UEX space station. */
  private static final int PLACES_PER_KIND = 4;

  @Autowired private ExchangeLocationLinks links;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> cities = new ArrayList<>();
  private final List<UUID> stations = new ArrayList<>();

  @AfterEach
  void tearDown() {
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    cities.forEach(id -> jdbc.update("DELETE FROM city WHERE id = ?", id));
    stations.forEach(id -> jdbc.update("DELETE FROM space_station WHERE id = ?", id));
  }

  @Test
  void theLinksOfManyPlacesOnCitiesAndStationsComeFromOneStatement() throws Exception {
    Map<UUID, Integer> onCities = new HashMap<>();
    Map<UUID, Integer> onStations = new HashMap<>();
    for (int i = 0; i < PLACES_PER_KIND; i++) {
      int uexCity = uexId();
      onCities.put(location(city(uexCity), null), uexCity);
      int uexStation = uexId();
      onStations.put(location(null, station(uexStation)), uexStation);
    }
    UUID unlinked = location(city(null), null);

    var byId = measured(() -> links.byId(locations));
    Map<UUID, Links> read = byId.result();

    assertThat(byId.statements())
        .as("statements for %d linked places (suspected N+1)", locations.size() - 1)
        .isOne();
    onCities.forEach((location, uex) ->
        assertThat(read.get(location).uex())
            .isEqualTo(new ExchangeLocationDto.UexRef(ExchangeCatalogService.KIND_CITY, uex)));
    onStations.forEach((location, uex) ->
        assertThat(read.get(location).uex())
            .isEqualTo(
                new ExchangeLocationDto.UexRef(ExchangeCatalogService.KIND_SPACE_STATION, uex)));
    assertThat(read.get(unlinked)).isEqualTo(Links.NONE);
  }

  /**
   * Draws a UEX id no seeded place uses.
   *
   * @return the id
   */
  private static int uexId() {
    return ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000_000);
  }

  /**
   * Inserts a city.
   *
   * @param uexId its UEX id, or {@code null} for none
   * @return its id
   */
  private @NotNull UUID city(@Nullable Integer uexId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO city (id, version, created_at, updated_at, name, id_city)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        id,
        "Links city " + id,
        uexId);
    cities.add(id);
    return id;
  }

  /**
   * Inserts a space station.
   *
   * @param uexId its UEX id
   * @return its id
   */
  private @NotNull UUID station(int uexId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_station (id, version, created_at, updated_at, name, id_space_station)"
            + " VALUES (?, 0, now(), now(), ?, ?)",
        id,
        "Links station " + id,
        uexId);
    stations.add(id);
    return id;
  }

  /**
   * Inserts a visible location on a city or a space station.
   *
   * @param city the city, or {@code null}
   * @param station the space station, or {@code null}
   * @return its id
   */
  private @NotNull UUID location(@Nullable UUID city, @Nullable UUID station) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id, space_station_id)"
            + " VALUES (?, ?, false, ?, ?)",
        id,
        "Links place " + id,
        city,
        station);
    locations.add(id);
    return id;
  }
}
