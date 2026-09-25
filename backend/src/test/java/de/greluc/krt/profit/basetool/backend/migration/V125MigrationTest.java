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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration test for {@code V125__drop_legacy_material_and_ship_type_columns.sql}: the migration is
 * recorded and {@code material.is_manual_entry} and {@code ship_type.description} are gone.
 */
@SpringBootTest
@ActiveProfiles("test")
class V125MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v125IsRecordedAsApplied() {
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = '125' AND success = true",
            Integer.class);
    assertEquals(1, applied == null ? 0 : applied, "V125 must be applied successfully");
  }

  @Test
  void v125DropsMaterialIsManualEntry() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'material' AND column_name = 'is_manual_entry'",
            Integer.class);
    assertEquals(0, count == null ? -1 : count, "material.is_manual_entry must be dropped by V125");
  }

  @Test
  void v125DropsShipTypeDescription() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'ship_type' AND column_name = 'description'",
            Integer.class);
    assertEquals(0, count == null ? -1 : count, "ship_type.description must be dropped by V125");
  }
}
