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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for {@code V346__add_game_item_p4k_attribute_columns.sql} (REQ-P4K-017): a row the
 * P4K seeded gets its name, manufacturer and mass as the P4K's own values, a row an earlier import
 * matched gets its name, no other row gets any, and the P4K class is one of the five classes.
 */
@SpringBootTest
@Transactional
class V346MigrationTest {

  private static final String SCRIPT =
      "/db/migration/V346__add_game_item_p4k_attribute_columns.sql";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void theBackfillCopiesASeededRowsValues_andLeavesEveryOtherRowEmpty() throws IOException {
    UUID seeded = insertItem("V346 Seeded shield", "P4K", 120.0);
    UUID uex = insertItem("V346 UEX shield", "UEX_ONLY", 80.0);

    runBackfill();

    assertThat(p4kValues(seeded))
        .containsEntry("p4k_name", "V346 Seeded shield")
        .containsEntry("p4k_mass", 120.0);
    assertThat(p4kValues(uex)).containsEntry("p4k_name", null).containsEntry("p4k_mass", null);
  }

  @Test
  void theBackfillKeepsTheNameOfARowAnEarlierImportMatched_andOnlyItsName() throws IOException {
    UUID matched = insertItem("V346 Matched shield", "BOTH", 95.0);
    jdbcTemplate.update("UPDATE game_item SET p4k_synced_at = now() WHERE id = ?", matched);

    runBackfill();

    assertThat(p4kValues(matched))
        .containsEntry("p4k_name", "V346 Matched shield")
        .containsEntry("p4k_mass", null);
  }

  @Test
  void theP4kClassIsOneOfTheFiveClasses() {
    UUID item = insertItem("V346 Classy shield", "BOTH", null);

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "UPDATE game_item SET p4k_item_class = 'PROTOTYPE' WHERE id = ?", item))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID insertItem(String name, String sourceSystems, Double mass) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO game_item (id, name, kind, source_systems, mass)
        VALUES (?, ?, 'GENERIC', ?, ?)
        """,
        id,
        name,
        sourceSystems,
        mass);
    return id;
  }

  private Map<String, Object> p4kValues(UUID id) {
    return jdbcTemplate.queryForMap("SELECT p4k_name, p4k_mass FROM game_item WHERE id = ?", id);
  }

  /** Runs the script's backfill statements in order; the columns it adds exist already. */
  private void runBackfill() throws IOException {
    try (InputStream in = V346MigrationTest.class.getResourceAsStream(SCRIPT)) {
      assertThat(in).isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      List<String> backfill =
          Arrays.stream(sql.split(";")).filter(s -> s.contains("UPDATE game_item")).toList();
      assertThat(backfill).hasSize(2);
      backfill.forEach(jdbcTemplate::execute);
    }
  }
}
