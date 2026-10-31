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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.BucketCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialCoverage.OrderCoverage;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The per-bucket coverage of job orders that a linked need shows (REQ-SUP-023). */
@ExtendWith(MockitoExtension.class)
class JobOrderMaterialCoverageTest {

  private static final QualityTierDto GOOD = QualityTierFixtures.goodDto();
  private static final QualityTierDto NONE = QualityTierFixtures.noneDto();

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderMaterialRequirementResolver requirementResolver;
  @Mock private JobOrderStockProjectionService stockProjectionService;

  private JobOrderMaterialCoverage coverage;
  private MaterialDto stileron;

  @BeforeEach
  void setUp() {
    coverage =
        new JobOrderMaterialCoverage(
            jobOrderRepository, requirementResolver, stockProjectionService);
    stileron =
        new MaterialDto(
            UUID.randomUUID(),
            "Stileron",
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
  }

  private static @NotNull JobOrder order(@NotNull JobOrderStatus status) {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(7);
    order.setHandle("Stileron run");
    order.setStatus(status);
    return order;
  }

  @Test
  void noOrdersReadNothing() {
    assertThat(coverage.coverageOf(Set.of())).isEmpty();

    verifyNoInteractions(jobOrderRepository, stockProjectionService);
  }

  @Test
  void anOpenOrderReportsItsBucketsWithTheAttributedStockMergingEqualBuckets() {
    JobOrder order = order(JobOrderStatus.IN_PROGRESS);
    List<MaterialRequirement> requirements =
        List.of(
            new MaterialRequirement(stileron, GOOD, 10.0),
            new MaterialRequirement(stileron, NONE, 40.0),
            new MaterialRequirement(stileron, GOOD, 2.4));
    OrderLinkedStockIndex index = mock(OrderLinkedStockIndex.class);
    when(jobOrderRepository.findAllWithMaterialRequirements(Set.of(order.getId())))
        .thenReturn(List.of(order));
    when(stockProjectionService.loadOrderLinkedStockIndex(List.of(order.getId())))
        .thenReturn(index);
    when(requirementResolver.requirementsOf(order)).thenReturn(requirements);
    when(index.bookedFor(order.getId(), requirements)).thenReturn(new double[] {10.0, 5.0, 2.4});

    Map<UUID, OrderCoverage> result = coverage.coverageOf(Set.of(order.getId()));

    OrderCoverage read = result.get(order.getId());
    assertThat(read.displayId()).isEqualTo(7);
    assertThat(read.handle()).isEqualTo("Stileron run");
    assertThat(read.bucket(stileron.id(), GOOD.id()))
        .isEqualTo(new BucketCoverage(stileron.id(), GOOD.id(), 12.4, 12.4));
    assertThat(read.bucket(stileron.id(), NONE.id()))
        .isEqualTo(new BucketCoverage(stileron.id(), NONE.id(), 40.0, 5.0));
    assertThat(read.bucket(UUID.randomUUID(), GOOD.id())).isNull();
  }

  @Test
  void aClosedOrderIsReportedWithoutBuckets() {
    JobOrder order = order(JobOrderStatus.COMPLETED);
    when(jobOrderRepository.findAllWithMaterialRequirements(Set.of(order.getId())))
        .thenReturn(List.of(order));
    when(stockProjectionService.loadOrderLinkedStockIndex(List.of()))
        .thenReturn(mock(OrderLinkedStockIndex.class));

    OrderCoverage read = coverage.coverageOf(Set.of(order.getId())).get(order.getId());

    assertThat(read.status()).isEqualTo(JobOrderStatus.COMPLETED);
    assertThat(read.buckets()).isEmpty();
    verify(requirementResolver, never()).requirementsOf(any());
  }
}
