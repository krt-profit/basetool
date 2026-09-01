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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the outstanding per-material need the order lookup projects for the Lager allocation
 * pickers (REQ-INV-039, #1740).
 *
 * <p>The two collaborators that decide the figure — {@link JobOrderMaterialRequirementResolver} and
 * {@link JobOrderStockProjectionService} — are wired as <b>real</b> instances rather than mocks:
 * what is under test is precisely that both order kinds reduce to the same buckets and that stock
 * is summed at each bucket's own quality floor, and a mocked resolver would assert only that the
 * service calls something.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobOrderReferenceNeedsTest {

  /** Material id every case books against. */
  private static final UUID MATERIAL_ID = UUID.randomUUID();

  /** The order under test; fixed so the stubbed stock rows can name it. */
  private static final UUID ORDER_ID = UUID.randomUUID();

  @Mock private JobOrderRepository jobOrderRepository;

  @Mock private InventoryItemRepository inventoryItemRepository;

  @Mock private OwnerScopeService ownerScopeService;

  @Mock private JobOrderMapper jobOrderMapper;

  @Mock private de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper squadronMapper;

  @Mock private JobOrderItemService jobOrderItemService;

  @Mock private MaterialMapper materialMapper;

  @Mock private MaterialClaimService materialClaimService;

  /** Real: the batching and quality-floor semantics are part of what is asserted. */
  @InjectMocks private JobOrderStockProjectionService stockProjectionService;

  /** Real: the two-kind normalisation is the thing under test. */
  @InjectMocks private JobOrderMaterialRequirementResolver requirementResolver;

  @InjectMocks private JobOrderQueryService queryService;

  /** Swaps the two behaviour-under-test collaborators in and opens the visibility gates. */
  @BeforeEach
  void wireRealCollaborators() {
    // @InjectMocks fills every collaborator with a MOCK; the two whose behaviour is asserted are
    // replaced by the real instances afterwards.
    ReflectionTestUtils.setField(
        queryService, "jobOrderStockProjectionService", stockProjectionService);
    ReflectionTestUtils.setField(queryService, "materialRequirementResolver", requirementResolver);
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.canSeeJobOrder(any(JobOrder.class))).thenReturn(true);
    when(jobOrderItemService.requiredMaterialIds(any(JobOrder.class))).thenReturn(Set.of());
    when(jobOrderItemService.requiredGameItemIds(any(JobOrder.class))).thenReturn(Set.of());
  }

  /** The figures stay opt-in, and asking for none costs no query. */
  @Test
  @DisplayName("withNeeds=false ships no figures and never touches the stock index")
  void withoutNeeds_shipsNothingAndSkipsTheQuery() {
    givenOrders(materialOrder(400.0, null));

    List<JobOrderReferenceDto> result = queryService.findAllActiveReference(false);

    assertTrue(result.get(0).materialNeeds().isEmpty(), "needs must be opt-in");
    verify(inventoryItemRepository, never()).findMaterialStockRowsByJobOrderIds(anyCollection());
  }

  /** The plain MATERIAL case: remaining requirement minus the stock already earmarked to it. */
  @Test
  @DisplayName("a MATERIAL line's need is its remaining amount minus the stock linked to it")
  void materialOrder_projectsRequiredMinusBooked() {
    givenOrders(materialOrder(400.0, null));
    givenLinkedStock(new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, null, 150.0));

    JobOrderMaterialNeedDto need = onlyNeed(queryService.findAllActiveReference(true));

    assertEquals(400.0, need.requiredAmount());
    assertEquals(150.0, need.bookedAmount());
    assertEquals(250.0, need.outstandingAmount());
    assertNull(need.qualityFloor(), "a line without a minQuality imposes no floor");
  }

  /** The floor is applied to the stock sum and reported, so a client can compare a grade to it. */
  @Test
  @DisplayName("a GOOD bucket ignores below-floor stock, and states the floor it applied")
  void materialOrder_sumsStockAtTheBucketQualityFloor() {
    givenOrders(materialOrder(400.0, 650));
    givenLinkedStock(
        new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, 700, 100.0),
        new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, 400, 90.0),
        new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, null, 80.0));

    JobOrderMaterialNeedDto need = onlyNeed(queryService.findAllActiveReference(true));

    assertEquals(650, need.qualityFloor(), "the client compares the booked grade against this");
    assertEquals(100.0, need.bookedAmount(), "only the 700-grade row is at or above the floor");
    assertEquals(300.0, need.outstandingAmount());
  }

  /** A crafting order has no material lines at all; its requirement is blueprint-derived. */
  @Test
  @DisplayName("an ITEM order contributes through its blueprint-derived requirements")
  void itemOrder_projectsTheAggregatedRequirement() {
    // The picker offers crafting orders for a material their blueprint consumes (REQ-ORDERS-018),
    // and those carry no job_order_material rows — reading `materials` would leave exactly the
    // orders that are hardest to look up by hand without a figure.
    JobOrder order = newOrder(JobOrderType.ITEM);
    givenOrders(order);
    when(jobOrderItemService.aggregateMaterials(order))
        .thenReturn(
            List.of(
                new AggregatedMaterialDto(
                    materialDto("SCU"), QualityRequirement.NONE, 120.0, null, List.of(), null)));
    givenLinkedStock(new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, null, 20.0));

    JobOrderMaterialNeedDto need = onlyNeed(queryService.findAllActiveReference(true));

    assertEquals(120.0, need.requiredAmount());
    assertEquals(100.0, need.outstandingAmount());
  }

  /** More stock earmarked than needed is "nothing left to gather", not a negative figure. */
  @Test
  @DisplayName("a fully covered bucket reports 0, never a negative overshoot")
  void overCoveredBucket_isFlooredAtZero() {
    givenOrders(materialOrder(100.0, null));
    givenLinkedStock(new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, null, 250.0));

    assertEquals(0.0, onlyNeed(queryService.findAllActiveReference(true)).outstandingAmount());
  }

  /** A counted material never surfaces a fraction. */
  @Test
  @DisplayName("a PIECE material's figures are whole units, not fractions")
  void pieceMaterial_roundsToWholeUnits() {
    // REQ-INV-027: every amount the split UI shows renders whole for a PIECE material. Rounding on
    // the projection is what keeps the picker label and the demand overview printing one number.
    givenOrders(materialOrder(10.0, null), "PIECE");
    givenLinkedStock(new JobOrderMaterialStockRow(ORDER_ID, MATERIAL_ID, null, 3.4));

    JobOrderMaterialNeedDto need = onlyNeed(queryService.findAllActiveReference(true));

    assertEquals(3.0, need.bookedAmount());
    assertEquals(7.0, need.outstandingAmount());
  }

  // ---------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------

  /**
   * Stubs the scoped active-order read.
   *
   * @param orders the orders the lookup should see.
   */
  private void givenOrders(JobOrder... orders) {
    when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of(orders));
  }

  /**
   * Stubs the active-order read together with the material's projected quantity type.
   *
   * @param order the order the lookup should see.
   * @param quantityType the projected material's quantity type ({@code SCU} / {@code PIECE}).
   */
  private void givenOrders(JobOrder order, String quantityType) {
    when(materialMapper.toDto(any(Material.class))).thenReturn(materialDto(quantityType));
    when(jobOrderRepository.findAllActiveWithMaterials()).thenReturn(List.of(order));
  }

  /**
   * Stubs the batched order-linked stock read.
   *
   * @param rows the linked inventory rows to index.
   */
  private void givenLinkedStock(JobOrderMaterialStockRow... rows) {
    when(inventoryItemRepository.findMaterialStockRowsByJobOrderIds(anyCollection()))
        .thenReturn(List.of(rows));
  }

  /**
   * Asserts the projection produced exactly one order with exactly one bucket.
   *
   * @param result the lookup result.
   * @return that single bucket.
   */
  private static JobOrderMaterialNeedDto onlyNeed(List<JobOrderReferenceDto> result) {
    assertEquals(1, result.size(), "one order was stubbed");
    assertEquals(1, result.get(0).materialNeeds().size(), "one bucket was stubbed");
    return result.get(0).materialNeeds().get(0);
  }

  /**
   * Builds a MATERIAL order carrying one line for {@link #MATERIAL_ID}.
   *
   * @param amount the line's remaining required amount.
   * @param minQuality the line's stored quality floor, or {@code null} for "Keine".
   * @return the order.
   */
  private JobOrder materialOrder(double amount, Integer minQuality) {
    JobOrder order = newOrder(JobOrderType.MATERIAL);
    Material material = new Material();
    material.setId(MATERIAL_ID);
    JobOrderMaterial line = new JobOrderMaterial();
    line.setId(UUID.randomUUID());
    line.setMaterial(material);
    line.setAmount(amount);
    line.setMinQuality(minQuality);
    order.setMaterials(new HashSet<>(Set.of(line)));
    when(materialMapper.toDto(material)).thenReturn(materialDto("SCU"));
    return order;
  }

  /**
   * Builds a bare order of the given kind carrying {@link #ORDER_ID}.
   *
   * @param type the order kind.
   * @return the order.
   */
  private static JobOrder newOrder(JobOrderType type) {
    JobOrder order = new JobOrder();
    order.setId(ORDER_ID);
    order.setDisplayId(1042);
    order.setStatus(JobOrderStatus.OPEN);
    order.setType(type);
    order.setMaterials(new HashSet<>());
    return order;
  }

  /**
   * The projected material, carrying only the fields the need calculation reads.
   *
   * @param quantityType the quantity type driving the rounding.
   * @return the material DTO.
   */
  private static MaterialDto materialDto(String quantityType) {
    return new MaterialDto(
        MATERIAL_ID,
        "Tungsten",
        "ORE",
        quantityType,
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
        0L);
  }
}
