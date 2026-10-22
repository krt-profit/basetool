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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the merge statements of {@code V310__ship_type_uex_alias_and_merge.sql} against the real
 * schema and proves that a UEX-only ship type and its aliased twin collapse onto the twin, with
 * every reference moved and the UEX columns carried over (REQ-DATA-026).
 *
 * <p>The statements are read from the migration file itself, so the test cannot drift from what
 * Flyway ran.
 */
@SpringBootTest
@Transactional
class V310MigrationTest {

  private static final String MIGRATION = "db/migration/V310__ship_type_uex_alias_and_merge.sql";

  private static final int UEX_ID = 990_310;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ShipTypeRepository shipTypes;

  @Test
  void theAliasTableHoldsTheCuratedPairsKeyedByUexVehicleId() {
    assertThat(
            jdbc.queryForObject(
                "SELECT class_name FROM ship_type_uex_alias WHERE uex_vehicle_id = 173",
                String.class))
        .isEqualTo("XNAA_SanTokYai");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ship_type_uex_alias", Integer.class))
        .isGreaterThanOrEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'ship_type_uex_alias'"
                    + " AND indexname = 'uk_ship_type_uex_alias_class_name'",
                Integer.class))
        .isOne();
  }

  @Test
  void theUexTwinCollapsesOntoTheAliasedRowAndTakesItsReferencesAlong() {
    UUID survivor = shipType("V310 Survivor", "TST_V310_Ship", null, "WIKI_ONLY");
    UUID loser = shipType("V310 Loser", null, UEX_ID, "UEX_ONLY");
    jdbc.update(
        "UPDATE ship_type SET uex_slug = 'v310-loser', is_bomber = TRUE WHERE id = ?", loser);
    alias(UEX_ID, "TST_V310_Ship");
    UUID owner = member();
    UUID ship = ship(owner, loser);
    UUID item = gameItem(loser);

    runMerge();

    assertThat(count("SELECT count(*) FROM ship_type WHERE id = ?", loser)).isZero();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT name, uex_vehicle_id, uex_slug, is_bomber, source_systems, version"
                + " FROM ship_type WHERE id = ?",
            survivor);
    assertThat(row.get("name")).isEqualTo("V310 Survivor");
    assertThat(row.get("uex_vehicle_id")).isEqualTo(UEX_ID);
    assertThat(row.get("uex_slug")).isEqualTo("v310-loser");
    assertThat(row.get("is_bomber")).isEqualTo(Boolean.TRUE);
    assertThat(row.get("source_systems")).isEqualTo("BOTH");
    assertThat(row.get("version")).isEqualTo(1L);
    assertThat(reference("SELECT ship_type_id FROM ship WHERE id = ?", ship)).isEqualTo(survivor);
    assertThat(jdbc.queryForObject("SELECT version FROM ship WHERE id = ?", Long.class, ship))
        .isEqualTo(1L);
    assertThat(reference("SELECT linked_ship_type_id FROM game_item WHERE id = ?", item))
        .isEqualTo(survivor);
  }

  @Test
  void theRepositoryLookupsFollowTheAliasInBothDirections() {
    UUID wiki = shipType("V310 Wiki", "TST_V310_Ship", null, "WIKI_ONLY");
    UUID uex = shipType("V310 Uex", null, UEX_ID, "UEX_ONLY");
    alias(UEX_ID, "TST_V310_Ship");

    assertThat(shipTypes.findByUexAliasOfVehicleId(UEX_ID))
        .extracting(ShipType::getId)
        .containsExactly(wiki);
    assertThat(shipTypes.findByUexAliasOfClassName("tst_v310_ship"))
        .map(ShipType::getId)
        .contains(uex);
    assertThat(shipTypes.findByClassNameOrUexAlias("TST_V310_SHIP"))
        .extracting(ShipType::getId)
        .containsExactly(wiki);

    jdbc.update("UPDATE ship_type SET class_name = NULL WHERE id = ?", wiki);

    assertThat(shipTypes.findByClassNameOrUexAlias("TST_V310_Ship"))
        .extracting(ShipType::getId)
        .containsExactly(uex);
  }

  @Test
  void aTwinThatIsNotUexOnlyIsLeftAlone() {
    UUID survivor = shipType("V310 Survivor", "TST_V310_Ship", null, "WIKI_ONLY");
    UUID other = shipType("V310 Both", null, UEX_ID, "BOTH");
    alias(UEX_ID, "TST_V310_Ship");

    runMerge();

    assertThat(count("SELECT count(*) FROM ship_type WHERE id = ?", other)).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT uex_vehicle_id FROM ship_type WHERE id = ?", Integer.class, survivor))
        .isNull();
  }

  @Test
  void anAmbiguousClassNameMergesNothing() {
    shipType("V310 First", "TST_V310_Dup", null, "WIKI_ONLY");
    shipType("V310 Second", "tst_v310_dup", null, "WIKI_ONLY");
    UUID loser = shipType("V310 Loser", null, UEX_ID, "UEX_ONLY");
    alias(UEX_ID, "TST_V310_Dup");

    runMerge();

    assertThat(count("SELECT count(*) FROM ship_type WHERE id = ?", loser)).isOne();
  }

  private void runMerge() {
    List<String> statements = migrationStatements();
    boolean merging = false;
    for (String statement : statements) {
      merging |= statement.startsWith("CREATE TEMP TABLE");
      if (merging) {
        jdbc.execute(statement);
      }
    }
    assertThat(merging).as("the migration's merge statements were found").isTrue();
  }

  private static List<String> migrationStatements() {
    try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return Arrays.stream(sql.split(";\\s*\\n"))
          .map(String::strip)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private UUID shipType(String name, String className, Integer uexId, String sourceSystems) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship_type (id, version, name, class_name, uex_vehicle_id, source_systems,"
            + " hidden) VALUES (?, 0, ?, ?, ?, ?, FALSE)",
        id,
        name,
        className,
        uexId,
        sourceSystems);
    return id;
  }

  private void alias(int uexId, String className) {
    jdbc.update(
        "INSERT INTO ship_type_uex_alias (uex_vehicle_id, class_name) VALUES (?, ?)",
        uexId,
        className);
  }

  private UUID member() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)", id, "v310-" + id);
    return id;
  }

  private UUID ship(UUID owner, UUID shipType) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, owner_id) VALUES (?, 0, ?, ?, ?)",
        id,
        "V310 Ship",
        shipType,
        owner);
    return id;
  }

  private UUID gameItem(UUID shipType) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, version, name, kind, linked_ship_type_id)"
            + " VALUES (?, 0, ?, 'GENERIC', ?)",
        id,
        "V310 Item " + id,
        shipType);
    return id;
  }

  private UUID reference(String sql, UUID id) {
    return jdbc.queryForObject(sql, UUID.class, id);
  }

  private int count(String sql, UUID id) {
    Integer n = jdbc.queryForObject(sql, Integer.class, id);
    return n == null ? 0 : n;
  }
}
