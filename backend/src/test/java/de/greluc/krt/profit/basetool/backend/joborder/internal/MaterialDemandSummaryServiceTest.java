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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the material-demand summary (REQ-ORDERS-034, REQ-UI-034): that only buckets with a
 * gap count, that the unit narrows the overview and that the limit cuts the rows but not the count.
 */
@ExtendWith(MockitoExtension.class)
class MaterialDemandSummaryServiceTest {

  private static final SquadronReferenceDto ALPHA =
      new SquadronReferenceDto(UUID.randomUUID(), "Alpha", "ALF");
  private static final SquadronReferenceDto BRAVO =
      new SquadronReferenceDto(UUID.randomUUID(), "Bravo", "BRV");

  @Mock private JobOrderMaterialDemandService overviewService;

  private MaterialDemandSummaryService service;

  @BeforeEach
  void setUp() {
    service = new MaterialDemandSummaryService(overviewService);
  }

  private static MaterialDemandRowDto row(String name, double outstanding) {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            name,
            null,
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            true,
            false,
            true,
            1L);
    return new MaterialDemandRowDto(
        material, "NONE", null, outstanding + 1, 1.0, 0.0, outstanding, List.of());
  }

  private void givenOverview(MaterialDemandGroupDto... groups) {
    when(overviewService.getMaterialDemandOverview())
        .thenReturn(new MaterialDemandOverviewDto(List.of(groups)));
  }

  @Test
  @DisplayName("only buckets with an outstanding amount are open")
  void skipsCoveredBuckets() {
    givenOverview(new MaterialDemandGroupDto(ALPHA, List.of(row("Titanium", 4), row("Quartz", 0))));

    MaterialDemandSummaryDto summary = service.summary(null, 5);

    assertThat(summary.openBuckets()).isEqualTo(1);
    assertThat(summary.rows())
        .extracting(r -> r.material().name(), MaterialDemandSummaryRowDto::outstandingAmount)
        .containsExactly(tuple("Titanium", 4.0));
  }

  @Test
  @DisplayName("the limit cuts the rows but not the count")
  void limitsTheRowsOnly() {
    givenOverview(
        new MaterialDemandGroupDto(ALPHA, List.of(row("A", 1), row("B", 2), row("C", 3))));

    MaterialDemandSummaryDto summary = service.summary(null, 2);

    assertThat(summary.openBuckets()).isEqualTo(3);
    assertThat(summary.rows()).extracting(r -> r.material().name()).containsExactly("A", "B");
  }

  @Test
  @DisplayName("a unit keeps only its own group, every row naming the unit")
  void narrowsToTheUnit() {
    givenOverview(
        new MaterialDemandGroupDto(ALPHA, List.of(row("A", 1))),
        new MaterialDemandGroupDto(BRAVO, List.of(row("B", 2))));

    MaterialDemandSummaryDto summary = service.summary(BRAVO.id(), 5);

    assertThat(summary.openBuckets()).isEqualTo(1);
    assertThat(summary.rows())
        .extracting(MaterialDemandSummaryRowDto::orgUnit)
        .containsExactly(BRAVO);
  }

  @Test
  @DisplayName("a unit without demand yields an empty summary")
  void aUnitWithoutDemandIsEmpty() {
    givenOverview(new MaterialDemandGroupDto(ALPHA, List.of(row("A", 1))));

    MaterialDemandSummaryDto summary = service.summary(UUID.randomUUID(), 5);

    assertThat(summary.openBuckets()).isZero();
    assertThat(summary.rows()).isEmpty();
  }
}
