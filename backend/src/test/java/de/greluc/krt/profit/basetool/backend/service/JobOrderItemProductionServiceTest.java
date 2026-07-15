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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionConsumptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link JobOrderItemProductionService#bookProduction}: the happy-path counter bump
 * + inventory reduction + audit, the amount / demand-coverage 422s, the non-item-order and
 * missing-slice guards, the stale-version 409, the depleted-row delete branch, the
 * no-materials/empty-consumption line, and a material marked "nicht ausbuchen" (skipped: recorded
 * but not booked out). Pure Mockito over the five collaborators; a Steel/SCU item line (amount 4,
 * per-unit demand 40) linked to a 100-SCU inventory entry earmarked in full to the order backs
 * every scenario.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemProductionServiceTest {

  private static final long LINE_VERSION = 3L;
  private static final long INVENTORY_VERSION = 7L;

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private AuditService auditService;
  @InjectMocks private JobOrderItemProductionService service;

  private UUID orderId;
  private UUID lineId;
  private UUID inventoryId;
  private UUID materialId;
  private JobOrder order;
  private JobOrderItem line;
  private Material material;
  private InventoryItem inventoryItem;

  @BeforeEach
  void setUp() {
    orderId = UUID.randomUUID();
    lineId = UUID.randomUUID();
    inventoryId = UUID.randomUUID();
    materialId = UUID.randomUUID();

    material = new Material();
    material.setId(materialId);
    material.setName("Steel");
    material.setQuantityType(QuantityType.SCU);

    line =
        JobOrderItem.builder()
            .id(lineId)
            .amount(4)
            .manufacturedAmount(0)
            .deliveredAmount(0)
            .build();
    line.setVersion(LINE_VERSION);
    // requiredQuantity holds the demand for the WHOLE ordered amount (4 units) → per-unit 40.
    JobOrderItemMaterial req =
        JobOrderItemMaterial.builder()
            .id(UUID.randomUUID())
            .material(material)
            .requiredQuantity(160.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build();
    line.addMaterial(req);

    order = JobOrder.builder().type(JobOrderType.ITEM).build();
    order.setId(orderId);
    order.setDisplayId(42);
    order.setHandle("Widget");
    order.addItem(line);

    inventoryItem = new InventoryItem();
    inventoryItem.setId(inventoryId);
    inventoryItem.setMaterial(material);
    inventoryItem.setAmount(100.0);
    inventoryItem.setVersion(INVENTORY_VERSION);
    // Variante C (REQ-INV-027): earmark the entry's full stock to this order, as the create path
    // does — the production guard reads this job-order slice.
    InventoryAllocations.addJobOrder(inventoryItem, order, 100.0, false);

    lenient().when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
    lenient()
        .when(inventoryItemRepository.findByIdForUpdate(inventoryId))
        .thenReturn(Optional.of(inventoryItem));
    lenient()
        .when(jobOrderItemService.toItemDtos(any()))
        .thenReturn(List.of(new JobOrderItemDto(lineId, null, null, 4, 1, 0, null, List.of(), 4L)));
  }

  @Test
  void bookProduction_happyPath_bumpsManufactured_reducesInventoryAndSlice_audits() {
    // Given — one unit is produced, consuming exactly its 40-SCU Steel demand from the entry.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of());

    // When
    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    // Then — the line advances to 1 manufactured, the entry drops to 60 and its slice follows.
    assertThat(result.id()).isEqualTo(lineId);
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    assertThat(inventoryItem.getAmount()).isEqualTo(60.0);
    assertThat(inventoryItem.getJobOrderAllocations()).hasSize(1);
    assertThat(inventoryItem.getJobOrderAllocations().get(0).getAmount()).isEqualTo(60.0);
    verify(inventoryItemRepository).save(inventoryItem);
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
  }

  @Test
  void bookProduction_amountExceedsRemainingToManufacture_throws422_noSave() {
    // Given — 3 of 4 already manufactured, so only 1 unit remains, but 2 are requested.
    line.setManufacturedAmount(3);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(2, LINE_VERSION, List.of(), List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    assertThat(line.getManufacturedAmount()).isEqualTo(3);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionUnderCoversDemand_throws422() {
    // Given — 30 SCU assigned against a 40-SCU demand: the plan under-covers the material.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 30.0, INVENTORY_VERSION)),
            List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumptionOverCoversDemand_throws422() {
    // Given — 50 SCU assigned against a 40-SCU demand: the plan over-covers the material.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 50.0, INVENTORY_VERSION)),
            List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ProductionAllocationException.class);
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_nonItemOrder_throwsBadRequest() {
    // Given — the order is a MATERIAL order, which has no item lines to produce.
    JobOrder materialOrder = JobOrder.builder().type(JobOrderType.MATERIAL).build();
    materialOrder.setId(orderId);
    when(jobOrderRepository.findById(orderId)).thenReturn(Optional.of(materialOrder));
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not an item order");
  }

  @Test
  void bookProduction_lineVersionMismatch_throwsOptimisticLock() {
    // Given — the echoed line version does not match the persisted one.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION + 996L,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(line.getManufacturedAmount()).isZero();
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bookProduction_consumedEntryHasNoOrderSlice_throwsIllegalState() {
    // Given — the coverage plan is exact (40 SCU for the 40-SCU demand), but the entry carries no
    // slice earmarked to this order, so the per-entry guard rejects it.
    inventoryItem.getJobOrderAllocations().clear();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of());

    // When & Then
    assertThatThrownBy(() -> service.bookProduction(orderId, lineId, dto))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not belong");
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bookProduction_consumesFullStock_deletesEntry_stillAudits() {
    // Given — the entry holds exactly the 40-SCU demand, earmarked in full to the order, so
    // consuming it depletes the row.
    inventoryItem.setAmount(40.0);
    inventoryItem.getJobOrderAllocations().clear();
    InventoryAllocations.addJobOrder(inventoryItem, order, 40.0, false);
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            1,
            LINE_VERSION,
            List.of(
                new JobOrderItemProductionConsumptionDto(
                    inventoryId, materialId, 40.0, INVENTORY_VERSION)),
            List.of());

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the depleted row is deleted (never saved, never clamped), the line still advances
    // and the audit trail is emitted.
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    verify(inventoryItemRepository).delete(inventoryItem);
    verify(inventoryItemRepository, never()).save(any());
    verify(materialExchangeOfferRepository, never()).clampOfferedAmountToStock(any(), anyDouble());
    verify(auditService, times(1))
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
  }

  @Test
  void bookProduction_lineWithoutMaterials_emptyConsumption_bumpsManufactured_noInventoryWrites() {
    // Given — an item line with no derivable material requirements: nothing is consumed.
    line.getMaterials().clear();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of());

    // When
    service.bookProduction(orderId, lineId, dto);

    // Then — the counter advances with no inventory access; only the booking audit is emitted.
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    verify(inventoryItemRepository, never()).findByIdForUpdate(any());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
  }

  @Test
  void bookProduction_materialMarkedSkip_notBookedOut_bumpsManufactured_noInventoryWrites() {
    // Given — the line's only material (Steel) is marked "nicht ausbuchen", so its 40-SCU demand is
    // dropped and the consumption plan is empty: production is recorded but no stock is touched.
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(1, LINE_VERSION, List.of(), List.of(materialId));

    // When
    JobOrderItemDto result = service.bookProduction(orderId, lineId, dto);

    // Then — the counter advances with no inventory access; only the booking audit is emitted and
    // the earmarked entry is left fully intact.
    assertThat(result.id()).isEqualTo(lineId);
    assertThat(line.getManufacturedAmount()).isEqualTo(1);
    assertThat(inventoryItem.getAmount()).isEqualTo(100.0);
    assertThat(inventoryItem.getJobOrderAllocations().get(0).getAmount()).isEqualTo(100.0);
    verify(inventoryItemRepository, never()).findByIdForUpdate(any());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
    verify(auditService, times(1))
        .record(eq(AuditEventType.JOB_ORDER_PRODUCTION_BOOKED), any(), any(), any(), any());
    verify(auditService, never())
        .record(eq(AuditEventType.INVENTORY_CONSUMED_BY_PRODUCTION), any(), any(), any(), any());
  }
}
