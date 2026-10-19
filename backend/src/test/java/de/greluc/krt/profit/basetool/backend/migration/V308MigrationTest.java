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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for {@code V308__decode_escaped_line_breaks_in_catalogue_descriptions.sql}
 * (REQ-DATA-025): a stored literal backslash-n becomes a real line break, other text is untouched.
 */
@SpringBootTest
@Transactional
class V308MigrationTest {

  private static final String SCRIPT =
      "/db/migration/V308__decode_escaped_line_breaks_in_catalogue_descriptions.sql";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v308TurnsEscapedLineBreaksIntoRealOnesAndLeavesOtherTextAlone() throws IOException {
    UUID escaped = insertMaterial("V308 escaped", "NDR: 08\\nHEI: 06\\n\\nToxic berries.");
    UUID plain = insertMaterial("V308 plain", "Volatile ore.");
    UUID multiline = insertMaterial("V308 multiline", "Line one\nLine two");

    runScript();

    assertThat(description(escaped)).isEqualTo("NDR: 08\nHEI: 06\n\nToxic berries.");
    assertThat(description(plain)).isEqualTo("Volatile ore.");
    assertThat(description(multiline)).isEqualTo("Line one\nLine two");
  }

  private UUID insertMaterial(String name, String description) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems, description)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY', ?)
        """,
        id,
        name,
        description);
    return id;
  }

  private String description(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT description FROM material WHERE id = ?", String.class, id);
  }

  private void runScript() throws IOException {
    try (InputStream in = V308MigrationTest.class.getResourceAsStream(SCRIPT)) {
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
