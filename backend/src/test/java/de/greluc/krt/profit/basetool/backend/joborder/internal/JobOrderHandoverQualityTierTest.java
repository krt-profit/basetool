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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderHandoverMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Booking a material handover against the chosen quality tier (REQ-ORDERS-038). */
@ExtendWith(MockitoExtension.class)
class JobOrderHandoverQualityTierTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private StockChangeObserver offerRatchet;
  @Mock private JobOrderHandoverMapper jobOrderHandoverMapper;
  @Mock private JobOrderMaterialRepository jobOrderMaterialRepository;
  @Mock private JobOrderService jobOrderService;
  @Mock private UserService userService;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private AuditService auditService;
  @InjectMocks private JobOrderHandoverService service;

  private UUID orderId;
  private JobOrder order;
  private Material material;
  private JobOrderMaterial goodLine;
  private JobOrderMaterial noneLine;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    order = new JobOrder();
    order.setId(orderId);
    order.setDisplayId(80);
    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Stileron");
    goodLine = line(QualityTierFixtures.good(), 0.1);
    noneLine = line(QualityTierFixtures.none(), 2.64);
    lenient().when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    lenient().when(jobOrderHandoverRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    lenient()
        .when(jobOrderHandoverMapper.toDto(any(JobOrderHandover.class)))
        .thenReturn(mock(JobOrderHandoverDto.class));
  }

  private JobOrderMaterial line(QualityTier tier, double amount) {
    JobOrderMaterial line = new JobOrderMaterial();
    line.setId(UUID.randomUUID());
    line.setMaterial(material);
    line.setQualityTier(tier);
    line.setAmount(amount);
    order.addMaterial(line);
    return line;
  }

  private InventoryItem stock(int quality, double amount) {
    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setMaterial(material);
    item.setQuality(quality);
    item.setAmount(amount);
    InventoryAllocations.addJobOrder(item, order, amount, false);
    lenient()
        .when(inventoryItemRepository.findByIdForUpdate(item.getId()))
        .thenReturn(Optional.of(item));
    return item;
  }

  private void handOver(JobOrderHandoverItemCreateDto... items) {
    service.createHandover(
        orderId, new JobOrderHandoverCreateDto(Instant.now(), "Receiver", null, List.of(items)));
  }

  @Test
  void chosenTier_isBookedAgainstThatLine() {
    InventoryItem row = stock(681, 2.64);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 1.0, null, "NONE"));

    assertThat(noneLine.getAmount()).isEqualTo(1.64, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(goodLine.getAmount()).isEqualTo(0.1);
  }

  @Test
  void chosenTier_isCaseInsensitive() {
    InventoryItem row = stock(681, 2.64);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, "good"));

    assertThat(goodLine.getAmount()).isZero();
    assertThat(noneLine.getAmount()).isEqualTo(2.64);
  }

  @Test
  void withoutChoice_theHighestTierTheQualityMeetsComesFirst_andTheRestSpills() {
    InventoryItem row = stock(681, 2.64);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 1.0, null, null));

    assertThat(goodLine.getAmount()).isZero();
    assertThat(noneLine.getAmount()).isEqualTo(1.74, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void withoutChoice_lowGradeStockOnlyServesTheLowTier() {
    InventoryItem row = stock(400, 2.0);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 2.0, null, null));

    assertThat(goodLine.getAmount()).isEqualTo(0.1);
    assertThat(noneLine.getAmount()).isEqualTo(0.64, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void aChosenTierAboveTheEntrysQuality_isRefused() {
    InventoryItem row = stock(400, 2.0);

    assertThatThrownBy(
            () -> handOver(new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, "GOOD")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderHandoverService.ERROR_QUALITY_BELOW_FLOOR);
  }

  @Test
  void aTierTheOrderDoesNotUseForTheMaterial_isRefused() {
    InventoryItem row = stock(950, 1.0);

    assertThatThrownBy(
            () -> handOver(new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, "EXCELLENT")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderHandoverService.ERROR_QUALITY_TIER_NOT_ON_ORDER);
  }

  @Test
  void stockBelowEveryFloorOfTheMaterial_isRefused() {
    order.getMaterials().remove(noneLine);
    InventoryItem row = stock(400, 1.0);

    assertThatThrownBy(
            () -> handOver(new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(JobOrderHandoverService.ERROR_QUALITY_BELOW_FLOOR);
  }

  @Test
  void oneEntrySplitAcrossBothTiers_fulfilsBothLines_andCompletesTheOrder() {
    InventoryItem row = stock(681, 2.74);

    handOver(
        new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, "GOOD"),
        new JobOrderHandoverItemCreateDto(row.getId(), 2.64, null, "NONE"));

    assertThat(goodLine.getAmount()).isZero();
    assertThat(noneLine.getAmount()).isZero();
    verify(inventoryItemRepository)
        .deleteJobOrderAllocationsByJobOrderAndMaterial(orderId, material.getId());
    verify(jobOrderService).completeJobOrderWithinTransaction(order);
  }

  @Test
  void fulfillingOneTier_keepsTheMaterialsAllocationsForTheOther() {
    InventoryItem row = stock(681, 2.64);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 0.1, null, "GOOD"));

    assertThat(goodLine.getAmount()).isZero();
    verify(inventoryItemRepository, never())
        .deleteJobOrderAllocationsByJobOrderAndMaterial(any(), any());
    verify(jobOrderService, never()).completeJobOrderWithinTransaction(any());
  }

  @Test
  void theHandedTier_isRecordedInTheAuditDetails() {
    InventoryItem row = stock(681, 2.64);

    handOver(new JobOrderHandoverItemCreateDto(row.getId(), 1.0, null, "NONE"));

    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_HANDED_OVER),
            eq(row.getId()),
            any(),
            any(),
            argThat(details -> details.toString().contains("quality=NONE")));
  }
}
