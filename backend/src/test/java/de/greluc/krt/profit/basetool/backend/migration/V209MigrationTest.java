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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestContainers-backed migration test for {@code V209__restore_mission_status_covering_index.sql}
 * (#1122). Asserts the restored 3-column status-covering index {@code
 * idx_mission_owning_org_unit_internal_status} exists and that the now-redundant 2-column {@code
 * idx_mission_owning_org_unit_internal} (a prefix the 3-column index already serves) was dropped.
 * The context boots the full migration chain, so the end state is asserted.
 */
@SpringBootTest
@ActiveProfiles("test")
class V209MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v209RestoresTheStatusCoveringCompositeIndex() {
    List<String> indexes = missionIndexes();
    assertTrue(
        indexes.contains("idx_mission_owning_org_unit_internal_status"),
        "the (owning_org_unit_id, is_internal, status) covering index must exist so the mission"
            + " list/search does not heap-filter on status");
  }

  @Test
  void v209DropsTheRedundantTwoColumnPrefixIndex() {
    List<String> indexes = missionIndexes();
    assertFalse(
        indexes.contains("idx_mission_owning_org_unit_internal"),
        "the 2-column (owning_org_unit_id, is_internal) index is redundant once the 3-column"
            + " covering index exists and must be dropped");
  }

  /** Returns every index name currently defined on the {@code mission} table. */
  private List<String> missionIndexes() {
    return jdbcTemplate.queryForList(
        "SELECT indexname FROM pg_indexes WHERE tablename = 'mission'", String.class);
  }
}
