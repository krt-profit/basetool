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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test of the personal-inventory location key, {@code
 * V344__key_personal_inventory_location_by_uuid.sql} (REQ-P4K-039): every row with a resolvable UEX
 * id lands on a {@code location} row, the name snapshot stays, and an unresolvable row keeps its
 * snapshot with a {@code null} key.
 *
 * <p>Each test rewinds the two tables to their V337 shape inside its rolled-back transaction and
 * then runs the script read from the migration file.
 */
@SpringBootTest
@Transactional
class V344MigrationTest {

  private static final String SCRIPT =
      "/db/migration/V344__key_personal_inventory_location_by_uuid.sql";

  @Autowired private JdbcTemplate jdbc;

  private UUID owner;

  @BeforeEach
  void rewindToV337() {
    jdbc.execute("ALTER TABLE personal_inventory_item DROP COLUMN location_id");
    jdbc.execute("ALTER TABLE location DROP COLUMN reference_only");
    jdbc.execute(
        "ALTER TABLE personal_inventory_item ADD COLUMN location_uex_id INTEGER,"
            + " ADD COLUMN location_type VARCHAR(20),"
            + " ADD CONSTRAINT ck_personal_inventory_item_location_type"
            + " CHECK (location_type IN ('CITY', 'SPACE_STATION'))");
    owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)", owner, "v344-" + owner);
  }

  @Test
  void aRowOnAPlaceWithALocationKeepsThatLocationAndItsSnapshot() throws IOException {
    UUID city = city(990_344_01, "V344 Live City", true);
    UUID location = location("V344 Live City", city, null);
    UUID item = item(990_344_01, "CITY", "V344 Old Snapshot");

    runScript();

    assertThat(locationOf(item)).isEqualTo(location);
    assertThat(snapshotOf(item)).isEqualTo("V344 Old Snapshot");
    assertThat(count("SELECT count(*) FROM location WHERE city_id = ?", city)).isOne();
    assertThat(referenceOnly(location)).isFalse();
  }

  @Test
  void aPlaceWithoutALocationGetsOneReferenceOnlyRowSharedByAllItsItems() throws IOException {
    UUID station = station(990_344_02, "V344 Dark Station");
    UUID first = item(990_344_02, "SPACE_STATION", "V344 Dark Station");
    UUID second = item(990_344_02, "SPACE_STATION", "V344 Dark Station");

    runScript();

    Map<String, Object> created =
        jdbc.queryForMap(
            "SELECT id, name, reference_only, hidden FROM location WHERE space_station_id = ?",
            station);
    assertThat(created.get("name")).isEqualTo("V344 Dark Station");
    assertThat(created.get("reference_only")).isEqualTo(Boolean.TRUE);
    assertThat(created.get("hidden")).isEqualTo(Boolean.FALSE);
    assertThat(locationOf(first)).isEqualTo(created.get("id"));
    assertThat(locationOf(second)).isEqualTo(created.get("id"));
  }

  @Test
  void aTakenNameFallsBackToTheNameWithItsUexId() throws IOException {
    location("V344 Twin", null, null);
    UUID city = city(990_344_03, "V344 Twin", false);
    UUID item = item(990_344_03, "CITY", "V344 Twin");

    runScript();

    String name =
        jdbc.queryForObject("SELECT name FROM location WHERE city_id = ?", String.class, city);
    assertThat(name).isEqualTo("V344 Twin (UEX city 99034403)");
    assertThat(locationOf(item))
        .isEqualTo(
            jdbc.queryForObject("SELECT id FROM location WHERE city_id = ?", UUID.class, city));
  }

  @Test
  void anUnresolvableCityAndStationKeepTheirSnapshotWithANullKey() throws IOException {
    UUID lostCity = item(990_344_04, "CITY", "V344 Lost City");
    UUID lostStation = item(990_344_04, "SPACE_STATION", "V344 Lost Station");

    runScript();

    assertThat(locationOf(lostCity)).isNull();
    assertThat(snapshotOf(lostCity)).isEqualTo("V344 Lost City");
    assertThat(locationOf(lostStation)).isNull();
    assertThat(snapshotOf(lostStation)).isEqualTo("V344 Lost Station");
  }

  @Test
  void theOldColumnsGoAndTheKeyIsAnIndexedForeignKeyThatClearsOnDelete() throws IOException {
    runScript();

    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'personal_inventory_item'"
                    + " AND column_name IN ('location_uex_id', 'location_type')"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'personal_inventory_item'"
                    + " AND indexname = 'idx_personal_inventory_item_location'"))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint"
                    + " WHERE conname = 'fk_personal_inventory_item_location'",
                String.class))
        .isEqualTo("n");
  }

  private UUID city(int uexId, String name, boolean live) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO city (id, id_city, name, is_available_live) VALUES (?, ?, ?, ?)",
        id,
        uexId,
        name,
        live);
    return id;
  }

  private UUID station(int uexId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_station (id, id_space_station, name, is_available_live)"
            + " VALUES (?, ?, ?, false)",
        id,
        uexId,
        name);
    return id;
  }

  private UUID location(String name, UUID cityId, UUID stationId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden, city_id, space_station_id)"
            + " VALUES (?, ?, false, ?, ?)",
        id,
        name,
        cityId,
        stationId);
    return id;
  }

  private UUID item(int uexId, String type, String snapshot) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO personal_inventory_item (id, version, created_at, updated_at, owner_user_id,"
            + " name, location_uex_id, location_type, location_name_snapshot, quantity)"
            + " VALUES (?, 0, now(), now(), ?, ?, ?, ?, ?, 1)",
        id,
        owner,
        "V344 item " + id,
        uexId,
        type,
        snapshot);
    return id;
  }

  private UUID locationOf(UUID item) {
    return jdbc.queryForObject(
        "SELECT location_id FROM personal_inventory_item WHERE id = ?", UUID.class, item);
  }

  private String snapshotOf(UUID item) {
    return jdbc.queryForObject(
        "SELECT location_name_snapshot FROM personal_inventory_item WHERE id = ?",
        String.class,
        item);
  }

  private Boolean referenceOnly(UUID location) {
    return jdbc.queryForObject(
        "SELECT reference_only FROM location WHERE id = ?", Boolean.class, location);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private void runScript() throws IOException {
    try (InputStream in = V344MigrationTest.class.getResourceAsStream(SCRIPT)) {
      assertThat(in).isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String statement : sql.split(";")) {
        if (!statement.isBlank()) {
          jdbc.execute(statement);
        }
      }
    }
  }
}
