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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderInventoryOwnerRedactor;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemBlueprintOwnersService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMaterialDemandService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMissionAllocationRedactor;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderQueryService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-Mockito unit tests for the material and linked-stock endpoints of {@link
 * JobOrderMaterialController}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderMaterialControllerTest {

  @Mock private JobOrderService jobOrderService;
  @Mock private JobOrderQueryService jobOrderQueryService;
  @Mock private JobOrderMaterialDemandService jobOrderMaterialDemandService;
  @Mock private JobOrderItemBlueprintOwnersService jobOrderItemBlueprintOwnersService;
  @Mock private JobOrderAccessPolicy jobOrderAccessPolicy;
  @Mock private JobOrderInventoryOwnerRedactor inventoryOwnerRedactor;
  @Mock private JobOrderMissionAllocationRedactor missionAllocationRedactor;

  @InjectMocks private JobOrderMaterialController controller;

  private static InventoryItemDto sampleInventoryItem() {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        750,
        5.0,
        false,
        false,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null,
        null);
  }

  @Test
  void getItemBlueprintOwners_delegatesToServiceAndReturnsResult() {
    UUID id = UUID.randomUUID();
    JobOrderItemBlueprintOwnersDto coverage =
        new JobOrderItemBlueprintOwnersDto(List.of(), List.of());
    when(jobOrderItemBlueprintOwnersService.getBlueprintOwners(id)).thenReturn(coverage);

    JobOrderItemBlueprintOwnersDto result = controller.getItemBlueprintOwners(id);

    assertThat(result).isSameAs(coverage);
    verify(jobOrderItemBlueprintOwnersService).getBlueprintOwners(id);
  }

  @Test
  void getMaterialDemand_returnsTheAggregatedOverviewUnchanged() {
    MaterialDemandOverviewDto overview =
        new MaterialDemandOverviewDto(
            List.of(
                new MaterialDemandGroupDto(
                    new SquadronReferenceDto(UUID.randomUUID(), "Alpha", "ALP"), List.of())));
    when(jobOrderMaterialDemandService.getMaterialDemandOverview()).thenReturn(overview);

    MaterialDemandOverviewDto result = controller.getMaterialDemand();

    assertThat(result).isSameAs(overview);
    verify(jobOrderMaterialDemandService).getMaterialDemandOverview();
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_responsibleSideViewer_delegatesUnredacted() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    InventoryItemDto inv = sampleInventoryItem();
    when(jobOrderQueryService.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId))
        .thenReturn(List.of(inv));
    when(missionAllocationRedactor.anonymiseUnreadableMissions(List.of(inv)))
        .thenReturn(List.of(inv));
    when(jobOrderAccessPolicy.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(true);

    List<InventoryItemDto> result =
        controller.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId);

    assertThat(result).containsExactly(inv);
    verify(inventoryOwnerRedactor, never()).redactInventoryItems(any());
  }

  @Test
  void getInventoryItemsForJobOrderMaterial_requestingSideViewer_redactsOwners() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    List<InventoryItemDto> raw = List.of(sampleInventoryItem());
    List<InventoryItemDto> redacted = List.of(sampleInventoryItem());
    when(jobOrderQueryService.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId))
        .thenReturn(raw);
    when(missionAllocationRedactor.anonymiseUnreadableMissions(raw)).thenReturn(raw);
    when(jobOrderAccessPolicy.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(false);
    when(inventoryOwnerRedactor.redactInventoryItems(raw)).thenReturn(redacted);

    List<InventoryItemDto> result =
        controller.getInventoryItemsForJobOrderMaterial(jobOrderId, materialId);

    assertThat(result).isSameAs(redacted);
    verify(inventoryOwnerRedactor).redactInventoryItems(raw);
  }

  @Test
  void getOrphanedLinkedInventory_requestingSideViewer_redactsOwners() {
    UUID jobOrderId = UUID.randomUUID();
    List<InventoryItemDto> raw = List.of(sampleInventoryItem());
    List<InventoryItemDto> redacted = List.of(sampleInventoryItem());
    when(jobOrderQueryService.getOrphanedLinkedInventory(jobOrderId)).thenReturn(raw);
    when(missionAllocationRedactor.anonymiseUnreadableMissions(raw)).thenReturn(raw);
    when(jobOrderAccessPolicy.canSeeJobOrderInventoryOwners(jobOrderId)).thenReturn(false);
    when(inventoryOwnerRedactor.redactInventoryItems(raw)).thenReturn(redacted);

    List<InventoryItemDto> result = controller.getOrphanedLinkedInventory(jobOrderId);

    assertThat(result).isSameAs(redacted);
    verify(inventoryOwnerRedactor).redactInventoryItems(raw);
  }

  @Test
  void unlinkMaterial_delegatesBothPathParameters() {
    UUID jobOrderId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();

    controller.unlinkMaterial(jobOrderId, materialId);

    verify(jobOrderService).unlinkMaterial(jobOrderId, materialId);
  }

  @Test
  void unlinkInventoryItem_delegatesBothPathParameters() {
    UUID jobOrderId = UUID.randomUUID();
    UUID inventoryItemId = UUID.randomUUID();

    controller.unlinkInventoryItem(jobOrderId, inventoryItemId);

    verify(jobOrderService).unlinkInventoryItem(jobOrderId, inventoryItemId);
  }
}
