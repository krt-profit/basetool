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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Every APPLY run records the share of items without an {@code itemClass} (REQ-OBS-022). */
@SpringBootTest
@Transactional
class P4kItemClassShareTest {

  @Autowired private P4kImportService service;
  @Autowired private P4kImportJobRepository jobRepository;
  @Autowired private P4kImportRunRepository runRepository;

  @Test
  void anApply_recordsTheShareOfItemsWithoutAClass() {
    P4kImportRuns runs = new P4kImportRuns(service, jobRepository);

    P4kImportRun run =
        runRepository
            .findById(runs.apply(P4kTestCatalogues.fixtureBytes(), false).runId())
            .orElseThrow();

    assertThat(run.getItemClassNullShare()).isEqualTo(6d / 9d);
  }

  @Test
  void anUnknownClassCountsAsAValue_andABlankOneAsNull() {
    ObjectNode root = P4kTestCatalogues.tree();
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.SHIELD).put("itemClass", "Exotic");
    P4kTestCatalogues.record(root, "items", P4kTestCatalogues.COOLER).put("itemClass", " ");
    P4kImportRuns runs = new P4kImportRuns(service, jobRepository);

    P4kImportRun run =
        runRepository
            .findById(runs.apply(P4kTestCatalogues.bytes(root), false).runId())
            .orElseThrow();

    assertThat(run.getItemClassNullShare()).isEqualTo(7d / 9d);
  }
}
