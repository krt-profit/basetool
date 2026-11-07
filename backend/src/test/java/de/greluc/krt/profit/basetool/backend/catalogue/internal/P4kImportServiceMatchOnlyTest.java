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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * The import matches and enriches; a record without a match creates nothing (REQ-P4K-008). The
 * catalogue carries no blueprint ingredients, so the commodity seeding of REQ-P4K-026 creates
 * nothing either.
 */
@SpringBootTest
@Transactional
class P4kImportServiceMatchOnlyTest {

  private static final List<String> TABLES =
      List.of(
          "game_item", "ship_type", "manufacturer", "material", "material_category", "blueprint");

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private P4kImportRunRepository runRepository;
  @Autowired private P4kImportChangeRepository changeRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private P4kImportRuns runs;

  @BeforeEach
  void setUp() {
    runs = new P4kImportRuns(service, jobRepository);
  }

  private Map<String, Long> rowCounts() {
    entityManager.flush();
    return TABLES.stream()
        .collect(
            Collectors.toMap(
                t -> t, t -> jdbcTemplate.queryForObject("SELECT count(*) FROM " + t, Long.class)));
  }

  private static byte[] withoutIngredients() {
    ObjectNode root = P4kTestCatalogues.tree();
    root.get("blueprints").forEach(blueprint -> ((ObjectNode) blueprint).remove("ingredients"));
    return P4kTestCatalogues.bytes(root);
  }

  @Test
  void anApplyWithoutTheCheckbox_createsNoRow_andListsEveryUnmatchedRecord() {
    Map<String, Long> before = rowCounts();

    P4kImportResultDto result = runs.apply(withoutIngredients(), false);

    assertThat(rowCounts()).isEqualTo(before);
    assertThat(result.items().created()).isZero();
    assertThat(result.ships().created()).isZero();
    List<P4kImportChange> changes = P4kImportRuns.report(changeRepository, result.runId());
    P4kImportRun run = runRepository.findById(result.runId()).orElseThrow();
    for (P4kImportRunKind kind : run.getKinds()) {
      long unmatchedRows =
          changes.stream()
              .filter(c -> c.getKind() == kind.getKind())
              .filter(c -> c.getChangeType() == P4kChangeType.UNMATCHED)
              .count();
      assertThat(unmatchedRows).as(kind.getKind().name()).isEqualTo(kind.getUnmatched());
    }
    assertThat(changes)
        .filteredOn(c -> c.getChangeType() == P4kChangeType.UNMATCHED)
        .filteredOn(c -> c.getKind() == P4kKind.SHIP)
        .allSatisfy(c -> {
          assertThat(c.getGuid()).isNotNull();
          assertThat(c.getClassName()).isNotBlank();
          assertThat(c.getRecordPath()).isNotBlank();
        });
  }
}
