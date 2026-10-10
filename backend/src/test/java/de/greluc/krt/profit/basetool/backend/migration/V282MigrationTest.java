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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration test for {@code V282__drop_superseded_quality_columns.sql}: the quality columns the
 * tier catalogue superseded are gone, together with their range check (REQ-ORDERS-036).
 */
@SpringBootTest
class V282MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @ParameterizedTest
  @CsvSource({
    "job_order_material, min_quality",
    "job_order_item_material, quality_requirement",
    "material_claim, quality_requirement"
  })
  void dropsTheSupersededColumn(String table, String column) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name = ?",
            Integer.class,
            table,
            column);

    assertThat(count).as(table + "." + column).isZero();
  }

  @Test
  void dropsTheRangeCheckOfTheOldFloor() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint"
                + " WHERE conname = 'ck_job_order_material_min_quality_range'",
            Integer.class);

    assertThat(count).isZero();
  }
}
