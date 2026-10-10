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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeDemandMaterialDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintVariantFamilyResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeDemandServiceTest {

  private static final UUID MEMBER = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderMaterialRequirementResolver requirementResolver;
  @Mock private JobOrderStockProjectionService stockProjectionService;
  @Mock private InventoryItemRepository inventoryRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private PersonalBlueprintRepository blueprintRepository;
  @Mock private BlueprintVariantFamilyResolver familyResolver;
  @Mock private OwnerScopeService ownerScopeService;
  @InjectMocks private ExchangeDemandService service;

  private OrderLinkedStockIndex stock;
  private Material quantanium;
  private MaterialDto quantaniumDto;

  @BeforeEach
  void setUp() {
    lenient().when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    stock = mock(OrderLinkedStockIndex.class);
    lenient().when(stockProjectionService.loadOrderLinkedStockIndex(any())).thenReturn(stock);
    lenient()
        .when(familyResolver.matchKey(anyString(), anyBoolean()))
        .thenAnswer(a -> a.getArgument(0) + (a.<Boolean>getArgument(1) ? "#family" : ""));
    quantanium = material("Quantanium", "SCU");
    quantaniumDto = dto(quantanium);
  }

  @Test
  void aMemberOfNoUnitSeesNoDemandAndNoOrderIsRead() {
    when(membershipRepository.findOrgUnitIdsByUserId(MEMBER)).thenReturn(Set.of());

    ExchangeOrgDemandDto demand = service.demand(MEMBER);

    assertThat(demand.materials()).isEmpty();
    assertThat(demand.items()).isEmpty();
    assertThat(demand.reason()).isNull();
    verify(jobOrderRepository, never()).findOpenForExchangeDemand(any(), any());
  }

  @Test
  void aMemberWhoFailsTheJobOrderGateGetsNotPermittedAndNothingIsRead() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    ExchangeOrgDemandDto demand = service.demand(MEMBER);

    assertThat(demand.materials()).isEmpty();
    assertThat(demand.items()).isEmpty();
    assertThat(demand.reason()).isEqualTo(ExchangeOrgDemandDto.Reason.NOT_PERMITTED);
    assertThat(demand.updatedAt()).isNotNull();
    verifyNoInteractions(
        membershipRepository, jobOrderRepository, requirementResolver, blueprintRepository);
  }

  @Test
  void aMemberWhoPassesTheJobOrderGateGetsTheDemandWithoutAReason() {
    JobOrder order = order(JobOrderType.MATERIAL);
    givenOrders(order);
    requires(order, QualityTierFixtures.noneDto(), 8.0, 0.0);

    ExchangeOrgDemandDto demand = service.demand(MEMBER);

    assertThat(demand.reason()).isNull();
    assertThat(demand.materials()).hasSize(1);
    verify(ownerScopeService).canViewJobOrders();
  }

  @Test
  void onlyTheUnitsTheMemberBelongsToAreAsked() {
    when(membershipRepository.findOrgUnitIdsByUserId(MEMBER)).thenReturn(Set.of(UNIT));
    when(jobOrderRepository.findOpenForExchangeDemand(any(), eq(Set.of(UNIT))))
        .thenReturn(List.of());

    service.demand(MEMBER);

    verify(jobOrderRepository)
        .findOpenForExchangeDemand(
            List.of(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS), Set.of(UNIT));
  }

  @Test
  void materialLinesSumTheOutstandingPerFloorAndSourceWithoutPerOrderDetail() {
    JobOrder first = order(JobOrderType.MATERIAL);
    JobOrder second = order(JobOrderType.MATERIAL);
    JobOrder items = order(JobOrderType.ITEM);
    JobOrder covered = order(JobOrderType.MATERIAL);
    givenOrders(first, second, items, covered);
    requires(first, QualityTierFixtures.goodDto(), 10.0, 4.0);
    requires(second, QualityTierFixtures.goodDto(), 6.5, 0.0);
    requires(items, QualityTierFixtures.noneDto(), 3.0, 0.0);
    requires(covered, QualityTierFixtures.noneDto(), 2.0, 5.0);
    Material ore = material("Quantanium (Ore)", "SCU");
    ore.setRefinedMaterial(quantanium);
    when(materialRepository.findAllByRefinedMaterialIdIn(Set.of(quantanium.getId())))
        .thenReturn(List.of(ore));

    List<ExchangeDemandMaterialDto> lines = service.demand(MEMBER).materials();

    assertThat(lines).hasSize(2);
    ExchangeDemandMaterialDto good =
        lines.stream().filter(l -> l.minQuality() == 650).findFirst().orElseThrow();
    assertThat(good.source()).isEqualTo("material-order");
    assertThat(good.openQuantity().amount()).isEqualByComparingTo(new BigDecimal("12.5"));
    assertThat(good.openQuantity().unit()).isEqualTo("SCU");
    assertThat(good.material().bt()).isEqualTo(quantanium.getId().toString());
    assertThat(good.rawRefs()).extracting(r -> r.bt()).containsExactly(ore.getId().toString());
    ExchangeDemandMaterialDto fromItems =
        lines.stream().filter(l -> l.minQuality() == 0).findFirst().orElseThrow();
    assertThat(fromItems.source()).isEqualTo("item-order");
    assertThat(fromItems.openQuantity().amount()).isEqualByComparingTo(new BigDecimal("3"));
  }

  @Test
  void itemLinesCountOrderedMinusDeliveredMinusEarmarkedAndWhetherTheMemberCanCraft() {
    JobOrder exact = order(JobOrderType.ITEM);
    exact.setCountBlueprintsWithVariants(false);
    JobOrder variants = order(JobOrderType.ITEM);
    GameItem arrowhead = gameItem("Arrowhead Sniper Rifle");
    GameItem helmet = gameItem("ORC-mkX Helmet");
    exact.getItems().add(line(arrowhead, "Arrowhead Sniper Rifle", 5, 1));
    variants.getItems().add(line(helmet, "ORC-mkX Helmet", 3, 0));
    variants.getItems().add(line(arrowhead, "Arrowhead Sniper Rifle", 2, 0));
    givenOrders(exact, variants);
    when(requirementResolver.requirementsOf(any())).thenReturn(List.of());
    when(inventoryRepository.findGameItemStockRowsByJobOrderIds(any()))
        .thenReturn(List.of(new JobOrderGameItemStockRow(exact.getId(), arrowhead.getId(), 1.0)));
    when(blueprintRepository.findOwnerProductByOwnerUserIdIn(Set.of(MEMBER)))
        .thenReturn(List.of(new BlueprintOwnerProduct(MEMBER, "ORC-mkX Helmet")));

    ExchangeOrgDemandDto demand = service.demand(MEMBER);

    assertThat(demand.items()).hasSize(2);
    assertThat(demand.items().get(0).item().bt()).isEqualTo(arrowhead.getId().toString());
    assertThat(demand.items().get(0).openQuantity().amount())
        .isEqualByComparingTo(new BigDecimal("5"));
    assertThat(demand.items().get(0).openQuantity().unit()).isEqualTo("PIECE");
    assertThat(demand.items().get(0).craftableByMe()).isFalse();
    assertThat(demand.items().get(1).item().bt()).isEqualTo(helmet.getId().toString());
    assertThat(demand.items().get(1).craftableByMe()).isTrue();
  }

  /**
   * Returns the given orders for the member's single unit.
   *
   * @param orders the open orders
   */
  private void givenOrders(JobOrder... orders) {
    when(membershipRepository.findOrgUnitIdsByUserId(MEMBER)).thenReturn(Set.of(UNIT));
    when(jobOrderRepository.findOpenForExchangeDemand(any(), any()))
        .thenReturn(new ArrayList<>(List.of(orders)));
  }

  /**
   * Gives an order one quantanium requirement and its linked stock.
   *
   * @param order the order
   * @param tier the quality tier
   * @param required the required amount
   * @param booked the stock linked to it
   */
  private void requires(
      @NotNull JobOrder order, @NotNull QualityTierDto tier, double required, double booked) {
    when(requirementResolver.requirementsOf(order))
        .thenReturn(List.of(new MaterialRequirement(quantaniumDto, tier, required)));
    when(stock.bookedFor(eq(order.getId()), anyList())).thenReturn(new double[] {booked});
  }

  private static @NotNull JobOrder order(@NotNull JobOrderType type) {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setStatus(JobOrderStatus.OPEN);
    order.setType(type);
    order.setItems(new LinkedHashSet<>());
    order.setMaterials(new LinkedHashSet<>());
    return order;
  }

  private static @NotNull JobOrderItem line(
      @NotNull GameItem item, @Nullable String output, int amount, int delivered) {
    JobOrderItem line = new JobOrderItem();
    line.setGameItem(item);
    Blueprint blueprint = new Blueprint();
    blueprint.setOutputName(output);
    line.setBlueprint(blueprint);
    line.setAmount(amount);
    line.setDeliveredAmount(delivered);
    return line;
  }

  private static @NotNull GameItem gameItem(@NotNull String name) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName(name);
    return item;
  }

  private static @NotNull Material material(@NotNull String name, @NotNull String unit) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setQuantityType(
        de.greluc.krt.profit.basetool.backend.model.QuantityType.valueOf(unit));
    return material;
  }

  private static @NotNull MaterialDto dto(@NotNull Material material) {
    return new MaterialDto(
        material.getId(),
        material.getName(),
        null,
        material.getQuantityType().name(),
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
}
