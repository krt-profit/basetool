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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for {@code V339__make_p4k_uuid_unique_per_kind.sql} (REQ-P4K-007): it clears every
 * duplicate group's {@code p4k_uuid}, keeps a unique one, then enforces uniqueness.
 */
@SpringBootTest
@Transactional
class V339MigrationTest {

  private static final String SCRIPT = "/db/migration/V339__make_p4k_uuid_unique_per_kind.sql";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v339ClearsEveryDuplicateGroup_keepsAUniqueGuid_andThenEnforcesUniqueness()
      throws IOException {
    for (String table :
        new String[] {"game_item", "ship_type", "manufacturer", "material", "blueprint"}) {
      jdbcTemplate.execute("DROP INDEX uq_" + table + "_p4k_uuid");
    }
    UUID shared = UUID.randomUUID();
    UUID unique = UUID.randomUUID();
    UUID duplicateA = insertMaterial("V339 Lifecure Medsticks", shared);
    UUID duplicateB = insertMaterial("V339 Redfin Energy Modulators", shared);
    UUID single = insertMaterial("V339 SLAM", unique);

    runScript();

    assertThat(p4kUuid(duplicateA)).isNull();
    assertThat(p4kUuid(duplicateB)).isNull();
    assertThat(p4kUuid(single)).isEqualTo(unique);
    assertThatThrownBy(() -> insertMaterial("V339 Uncut SLAM", unique))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UUID insertMaterial(String name, UUID p4kUuid) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems, p4k_uuid)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY', ?)
        """,
        id,
        name,
        p4kUuid);
    return id;
  }

  private UUID p4kUuid(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT p4k_uuid FROM material WHERE id = ?", UUID.class, id);
  }

  private void runScript() throws IOException {
    try (InputStream in = V339MigrationTest.class.getResourceAsStream(SCRIPT)) {
      assertThat(in).isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String statement : sql.split(";")) {
        if (!statement.isBlank()) {
          jdbcTemplate.execute(statement);
        }
      }
    }
  }
}
