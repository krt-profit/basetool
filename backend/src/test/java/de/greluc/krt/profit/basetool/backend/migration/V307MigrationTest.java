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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration test for {@code V307__add_game_item_item_class.sql} (REQ-DATA-024): the nullable {@code
 * item_class} column and the CHECK constraint holding exactly the {@link ShipComponentClass}
 * values.
 */
@SpringBootTest
class V307MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v307AddsANullableShortItemClassColumn() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT data_type, is_nullable, character_maximum_length"
                + " FROM information_schema.columns"
                + " WHERE table_name = 'game_item' AND column_name = 'item_class'");

    assertThat(rows).hasSize(1);
    Map<String, Object> column = rows.getFirst();
    assertThat(column.get("data_type")).isEqualTo("character varying");
    assertThat(column.get("is_nullable")).isEqualTo("YES");
    assertThat(((Number) column.get("character_maximum_length")).intValue()).isEqualTo(16);
  }

  @Test
  void v307CheckConstraintAllowsExactlyTheEnumValues() {
    String definition =
        jdbcTemplate.queryForObject(
            "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid = c.conrelid"
                + " WHERE t.relname = 'game_item' AND c.contype = 'c'"
                + " AND c.conname = 'ck_game_item_item_class'",
            String.class);

    assertThat(definition).isNotNull();
    List<String> allowed =
        Pattern.compile("'([A-Z_]+)'").matcher(definition).results().map(m -> m.group(1)).toList();
    assertThat(allowed)
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(ShipComponentClass.values()).map(Enum::name).toList());
  }
}
