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
import org.springframework.transaction.annotation.Transactional;

/**
 * TestContainers-backed migration test for {@code V126__create_personal_blueprint.sql}. Asserts the
 * {@code personal_blueprint} table exists with its columns and types, the {@code (owner_sub,
 * product_key)} UNIQUE constraint, and that the unique constraint actually rejects a duplicate
 * product for the same owner while allowing the same product for a different owner.
 */
@SpringBootTest
@ActiveProfiles("test")
// The owner rows this class inserts must not survive it: the test database is shared across the
// suite, and a leftover login-capable app_user shifts the totals other classes assert over.
@Transactional
class V126MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v126CreatesPersonalBlueprintTable() {
    Map<String, String> types = dataTypesOf("personal_blueprint");
    assertEquals("uuid", types.get("id"));
    assertEquals("bigint", types.get("version"));
    assertEquals("timestamp with time zone", types.get("created_at"));
    assertEquals("timestamp with time zone", types.get("updated_at"));
    // V235 (issue #1638) recast owner_sub to UUID so it can carry a foreign key to app_user(id);
    // V126 created it as VARCHAR(64). The later migration wins -- this asserts the schema as it
    // actually stands after the full chain, which is what ddl-auto=validate checks the entity
    // against.
    assertEquals("uuid", types.get("owner_sub"));
    assertEquals("character varying", types.get("product_key"));
    assertEquals("character varying", types.get("product_name"));
    assertEquals("uuid", types.get("output_item_id"));
    assertEquals("timestamp with time zone", types.get("acquired_at"));
    assertEquals("character varying", types.get("note"));

    Integer uk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'personal_blueprint' AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_personal_blueprint_owner_product'",
            Integer.class);
    assertEquals(1, uk == null ? 0 : uk, "(owner_sub, product_key) UNIQUE constraint must exist");
  }

  @Test
  void v126UniqueConstraint_rejectsDuplicateProductForSameOwner() {
    UUID owner = owner();
    insertOwned(owner, "calico legs tactical");
    assertThrows(
        DataAccessException.class,
        () -> insertOwned(owner, "calico legs tactical"),
        "the same owner must not own the same product twice");
  }

  @Test
  void v126UniqueConstraint_allowsSameProductForDifferentOwners() {
    insertOwned(owner(), "arclight pistol");
    UUID second = owner();
    assertDoesNotThrow(
        () -> insertOwned(second, "arclight pistol"),
        "different owners may each own the same product");
  }

  /**
   * Creates an {@code app_user} row and returns its id.
   *
   * <p>Needed since V235: {@code owner_sub} is a foreign key to {@code app_user(id)}, so a
   * blueprint for an invented owner no longer inserts at all.
   *
   * @return the new user's id, usable as an {@code owner_sub}
   */
  private UUID owner() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO app_user (id, username) VALUES (?, ?)", id, "u-" + id);
    return id;
  }

  private void insertOwned(UUID ownerSub, String productKey) {
    jdbcTemplate.update(
        "INSERT INTO personal_blueprint "
            + "(id, owner_sub, product_key, product_name) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        ownerSub,
        productKey,
        productKey);
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
