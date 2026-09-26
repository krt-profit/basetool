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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration test for {@code V127__create_blueprint_external_alias.sql}: table shape, the {@code
 * source_system} CHECK, and case-insensitive uniqueness of {@code (source_system, external_name)}
 * (REQ-INV-020).
 */
@SpringBootTest
@ActiveProfiles("test")
class V127MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v127CreatesBlueprintExternalAliasTable() {
    Map<String, String> types = dataTypesOf("blueprint_external_alias");
    assertEquals("uuid", types.get("id"));
    assertEquals("bigint", types.get("version"));
    assertEquals("character varying", types.get("source_system"));
    assertEquals("character varying", types.get("external_name"));
    assertEquals("character varying", types.get("product_key"));
    assertEquals("character varying", types.get("product_name"));
    assertEquals("uuid", types.get("output_item_id"));
    assertEquals("text", types.get("note"));
    assertEquals("character varying", types.get("created_by"));
  }

  /**
   * Verifies that the case-sensitive V127 unique constraint is replaced by the V176 unique index on
   * {@code (source_system, LOWER(external_name))} and that the source-system CHECK remains
   * (REQ-INV-020).
   */
  @Test
  void v127UniqueConstraintSupersededByV176CaseInsensitiveIndex() {
    Integer uniqueCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'blueprint_external_alias' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_blueprint_external_alias_source_external_name'",
            Integer.class);
    assertEquals(
        0,
        uniqueCount == null ? 0 : uniqueCount,
        "V127 case-sensitive UNIQUE constraint must be dropped by V176");

    Integer caseInsensitiveUniqueIndexCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'blueprint_external_alias' "
                + "AND indexname = 'uq_blueprint_external_alias_source_lower_name' "
                + "AND indexdef LIKE 'CREATE UNIQUE INDEX%'",
            Integer.class);
    assertEquals(
        1,
        caseInsensitiveUniqueIndexCount == null ? 0 : caseInsensitiveUniqueIndexCount,
        "V176 case-insensitive unique index must exist and be UNIQUE");

    assertConstraintExists("CHECK", "chk_blueprint_external_alias_source_system");
  }

  @Test
  void v127UniqueConstraint_rejectsDuplicateExternalName() {
    insertAlias("Calico Legs Tactical", "calico legs tactical");
    assertThrows(
        DataAccessException.class,
        () -> insertAlias("Calico Legs Tactical", "calico legs tactical"),
        "the same (source_system, external_name) must not be inserted twice");
  }

  @Test
  void v176Index_rejectsCaseVariantExternalName() {
    insertAlias("Gallant Rifle Battery", "gallant rifle battery");
    assertThrows(
        DataAccessException.class,
        () -> insertAlias("GALLANT RIFLE BATTERY", "gallant rifle battery"),
        "a case-only variant of an existing external name must be rejected by the V176 index");
  }

  @Test
  void v127SourceSystemCheck_rejectsUnknownSource() {
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                "INSERT INTO blueprint_external_alias "
                    + "(id, source_system, external_name, product_key, product_name) "
                    + "VALUES (?, 'UEX', 'x', 'x', 'x')",
                UUID.randomUUID()),
        "source_system outside the CHECK list must be rejected");
  }

  @Test
  void v127SourceSystemCheck_acceptsScmdb() {
    assertDoesNotThrow(() -> insertAlias("Arclight Pistol", "arclight pistol"));
  }

  private void insertAlias(String externalName, String productKey) {
    jdbcTemplate.update(
        "INSERT INTO blueprint_external_alias "
            + "(id, source_system, external_name, product_key, product_name) "
            + "VALUES (?, 'SCMDB', ?, ?, ?)",
        UUID.randomUUID(),
        externalName,
        productKey,
        externalName);
  }

  private void assertConstraintExists(String constraintType, String constraintName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'blueprint_external_alias' AND constraint_type = ? "
                + "AND constraint_name = ?",
            Integer.class,
            constraintType,
            constraintName);
    assertEquals(
        1, count == null ? 0 : count, constraintType + " " + constraintName + " must exist");
  }

  private Map<String, String> dataTypesOf(String tableName) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?",
            tableName);
    Map<String, String> out = new HashMap<>();
    for (Map<String, Object> row : rows) {
      out.put(((String) row.get("column_name")).toLowerCase(), (String) row.get("data_type"));
    }
    return out;
  }
}
