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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestContainers-backed migration test for {@code V219__add_job_order_item_manufactured_amount.sql}
 * (REQ-ORDERS-025, "Herstellung"). Asserts the new {@code job_order_item.manufactured_amount}
 * column ships as a {@code NOT NULL INTEGER DEFAULT 0} and that the three invariant CHECK
 * constraints guarding {@code 0 <= delivered_amount <= manufactured_amount <= amount} — {@code
 * chk_job_order_item_manufactured} (>= 0), {@code chk_job_order_item_manufactured_ge_delivered} and
 * {@code chk_job_order_item_manufactured_le_amount} — exist. The context boots the full migration
 * chain and Hibernate {@code ddl-auto=validate}, so a mismatch between the {@code
 * JobOrderItem.manufacturedAmount} mapping and this migration also fails the test.
 */
@SpringBootTest
@ActiveProfiles("test")
class V219MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v219AddsManufacturedAmountColumnAsNotNullIntegerDefaultZero() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_name = 'job_order_item' AND column_name = 'manufactured_amount'");
    assertEquals(
        1, rows.size(), "the job_order_item.manufactured_amount column must exist exactly once");

    Map<String, Object> column = rows.get(0);
    assertEquals(
        "integer", column.get("data_type"), "manufactured_amount must be an INTEGER counter");
    assertEquals(
        "NO", column.get("is_nullable"), "manufactured_amount must be NOT NULL (defaulted to 0)");

    Object columnDefault = column.get("column_default");
    assertNotNull(
        columnDefault, "manufactured_amount must carry a DEFAULT so legacy rows backfill");
    assertTrue(
        columnDefault.toString().startsWith("0"),
        "manufactured_amount must default to 0 but was " + columnDefault);
  }

  @Test
  void v219AddsTheThreeManufacturedInvariantCheckConstraints() {
    assertCheckConstraintExists("chk_job_order_item_manufactured");
    assertCheckConstraintExists("chk_job_order_item_manufactured_ge_delivered");
    assertCheckConstraintExists("chk_job_order_item_manufactured_le_amount");
  }

  @Test
  void v219DoesNotLeaveTheManufacturedColumnNullable() {
    // A regression guard alongside the column shape: an absent NOT NULL flag would let the
    // production-booking counter go null and silently break the delivery gate (delivered <=
    // manufactured), so pin the nullability explicitly rather than only asserting the type.
    String isNullable =
        jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'job_order_item' AND column_name = 'manufactured_amount'",
            String.class);
    assertFalse(
        "YES".equals(isNullable), "manufactured_amount must never be nullable (REQ-ORDERS-025)");
  }

  /**
   * Asserts a named CHECK constraint exists on the {@code job_order_item} table, looked up by
   * {@code conname} in {@code pg_constraint} ({@code contype = 'c'}) joined to its owning relation.
   *
   * @param constraintName the {@code pg_constraint.conname} the migration declares
   */
  private void assertCheckConstraintExists(String constraintName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "WHERE t.relname = 'job_order_item' AND c.contype = 'c' AND c.conname = ?",
            Integer.class,
            constraintName);
    assertEquals(
        1,
        count == null ? 0 : count,
        "CHECK constraint " + constraintName + " must exist on job_order_item");
  }
}
