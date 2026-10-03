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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for V261–V265: the quality-tier catalogue and its seed (REQ-ORDERS-036), the tier
 * references on requirements and claims, and the 0–1000 bound on every quality column
 * (REQ-DATA-023).
 */
@SpringBootTest
@Transactional
class V261QualityTierMigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void seedsTheTwoTiersWithTheirFixedIds() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT id::text AS id, code, min_quality, active FROM quality_tier"
                + " WHERE code IN ('NONE', 'GOOD') ORDER BY min_quality");

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("id", "6b1f2e0a-3c1d-4f5e-9a10-000000000000")
        .containsEntry("code", "NONE")
        .containsEntry("min_quality", 0)
        .containsEntry("active", true);
    assertThat(rows.get(1))
        .containsEntry("id", "6b1f2e0a-3c1d-4f5e-9a10-000000000650")
        .containsEntry("code", "GOOD")
        .containsEntry("min_quality", 650);
  }

  @Test
  void refusesAFloorAbove1000() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO quality_tier (id, code, min_quality, label_de, label_en)"
                        + " VALUES (gen_random_uuid(), 'TOOHIGH', 1001, 'x', 'x')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void refusesASecondTierWithTheSameFloor() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO quality_tier (id, code, min_quality, label_de, label_en)"
                        + " VALUES (gen_random_uuid(), 'TWIN', 650, 'x', 'x')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void refusesAnInactiveBaseTier() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update("UPDATE quality_tier SET active = FALSE WHERE min_quality = 0"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void requirementsAndClaimsReferenceATierAndMayNoLongerOmitIt() {
    for (String table :
        List.of("job_order_material", "job_order_item_material", "material_claim")) {
      String nullable =
          jdbcTemplate.queryForObject(
              "SELECT is_nullable FROM information_schema.columns"
                  + " WHERE table_name = ? AND column_name = 'quality_tier_id'",
              String.class,
              table);
      assertThat(nullable).as(table + ".quality_tier_id").isEqualTo("NO");
    }
  }

  @Test
  void boundsEveryQualityColumnTo0To1000() {
    List<String> constraints =
        jdbcTemplate.queryForList(
            "SELECT conname FROM pg_constraint WHERE conname LIKE 'ck\\_%quality\\_range'",
            String.class);

    assertThat(constraints)
        .contains(
            "ck_inventory_item_quality_range",
            "ck_refinery_good_quality_range",
            "ck_job_order_handover_item_quality_range",
            "ck_blueprint_ingredient_min_quality_range",
            "ck_blueprint_requirement_modifier_quality_range",
            "ck_blueprint_modifier_segment_quality_range");
  }

  @Test
  void claimUniquenessMovedToTheTierColumn() {
    Integer old =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_material_claim_bucket_org_unit'",
            Integer.class);
    Integer current =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes"
                + " WHERE indexname = 'uq_material_claim_tier_bucket_org_unit'",
            Integer.class);

    assertThat(old).isZero();
    assertThat(current).isEqualTo(1);
  }
}
