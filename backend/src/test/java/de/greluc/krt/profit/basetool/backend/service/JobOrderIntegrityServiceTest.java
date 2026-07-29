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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderItemBlueprintDrift;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link JobOrderIntegrityService} (REQ-ORDERS-033): the sweep reports every
 * ordered-item line whose blueprint drifted away from the ordered item, and reports a clean run
 * when the detection query comes back empty.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderIntegrityServiceTest {

  @Mock private JobOrderItemRepository jobOrderItemRepository;
  @InjectMocks private JobOrderIntegrityService service;

  // covers REQ-ORDERS-033 (drifted lines are collected and counted for the integrity gauge)
  @Test
  void verifyReportsEveryDriftedLine() {
    // Given the production case that motivated the check: a Cryo-Star SL line still pointing at
    // the HeatSink blueprint after an SC-Wiki re-point, plus a second, unrelated drift.
    JobOrderItemBlueprintDrift cooler =
        new JobOrderItemBlueprintDrift(
            UUID.randomUUID(),
            UUID.randomUUID(),
            75,
            "Cryo-Star SL",
            "HeatSink",
            "BP_CRAFT_COOL_TYDT_S02_HeatSink_SCItem");
    JobOrderItemBlueprintDrift rifle =
        new JobOrderItemBlueprintDrift(
            UUID.randomUUID(),
            UUID.randomUUID(),
            61,
            "Karna Rifle",
            "Karna \"Valor\" Rifle",
            "BP_CRAFT_ksar_rifle_energy_01_blue_gold");
    when(jobOrderItemRepository.findBlueprintOutputDrift()).thenReturn(List.of(cooler, rifle));

    JobOrderIntegrityService.IntegrityReport report = service.verify();

    assertThat(report.blueprintDrift()).containsExactly(cooler, rifle);
    assertThat(report.violationCount()).isEqualTo(2);
    assertThat(report.isClean()).isFalse();
  }

  // covers REQ-ORDERS-033 (a consistent database reports clean, so the gauge falls back to 0)
  @Test
  void verifyReportsCleanWhenNoLineDrifted() {
    when(jobOrderItemRepository.findBlueprintOutputDrift()).thenReturn(List.of());

    JobOrderIntegrityService.IntegrityReport report = service.verify();

    assertThat(report.blueprintDrift()).isEmpty();
    assertThat(report.violationCount()).isZero();
    assertThat(report.isClean()).isTrue();
  }
}
