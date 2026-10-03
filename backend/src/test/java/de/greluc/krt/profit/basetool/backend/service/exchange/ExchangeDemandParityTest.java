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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDemandRowDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeDemandMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeOrgDemandDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintVariantFamilyResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialDemandService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.service.MaterialClaimService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.ScopePredicate;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/**
 * Pins that the exchange's anonymised org demand (REQ-XCH-018) equals the web's Materialbedarf
 * (REQ-ORDERS-034) for the same orders: per material and quality floor, summed over the member's
 * units, the exchange's open quantity is the web's {@code required - booked} clamped at zero, and a
 * fully covered bucket is absent from the exchange. Both are withheld by the same job-order gate.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeDemandParityTest {

  private static final UUID MEMBER = UUID.randomUUID();
  private static final QualityTierDto GOOD = QualityTierFixtures.goodDto();
  private static final QualityTierDto NONE = QualityTierFixtures.noneDto();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderMaterialRequirementResolver requirementResolver;
  @Mock private JobOrderStockProjectionService stockProjectionService;
  @Mock private InventoryItemRepository inventoryRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private PersonalBlueprintRepository blueprintRepository;
  @Mock private BlueprintVariantFamilyResolver familyResolver;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private MaterialClaimService materialClaimService;
  @Mock private SquadronMapper squadronMapper;

  private ExchangeDemandService exchange;
  private JobOrderMaterialDemandService web;
  private OrderLinkedStockIndex stock;
  private final Map<UUID, Map<TierBucket, Double>> bookedByOrder = new HashMap<>();
  private MaterialDto agricium;
  private MaterialDto beryl;

  @BeforeEach
  void setUp() {
    exchange =
        new ExchangeDemandService(
            membershipRepository,
            jobOrderRepository,
            requirementResolver,
            stockProjectionService,
            inventoryRepository,
            materialRepository,
            blueprintRepository,
            familyResolver,
            ownerScopeService);
    web =
        new JobOrderMaterialDemandService(
            jobOrderRepository,
            ownerScopeService,
            requirementResolver,
            stockProjectionService,
            materialClaimService,
            squadronMapper);
    stock = mock(OrderLinkedStockIndex.class, withSettings().strictness(Strictness.LENIENT));
    lenient()
        .when(stock.bookedFor(any(), anyList()))
        .thenAnswer(a -> bookedFor(a.getArgument(0), a.getArgument(1)));
    lenient().when(stockProjectionService.loadOrderLinkedStockIndex(any())).thenReturn(stock);
    lenient()
        .when(squadronMapper.orgUnitToReferenceDto(any()))
        .thenAnswer(
            a -> {
              OrgUnit unit = a.getArgument(0);
              return new SquadronReferenceDto(unit.getId(), unit.getName(), unit.getShorthand());
            });
    agricium = material("Agricium");
    beryl = material("Beryl");
  }

  @Test
  void theOrgDemandEqualsTheWebGapPerMaterialAndQualityFloor() {
    Squadron iridium = squadron("IRI");
    JobOrder first = order(iridium, 1, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    JobOrder second = order(iridium, 2, JobOrderType.MATERIAL, JobOrderStatus.IN_PROGRESS);
    JobOrder items = order(iridium, 3, JobOrderType.ITEM, JobOrderStatus.OPEN);
    requires(
        first,
        new MaterialRequirement(agricium, GOOD, 100.0),
        new MaterialRequirement(beryl, NONE, 30.0));
    requires(second, new MaterialRequirement(agricium, NONE, 20.0));
    requires(items, new MaterialRequirement(agricium, GOOD, 12.5));
    booked(first, agricium, GOOD, 40.0);
    booked(first, beryl, NONE, 30.0);
    booked(second, agricium, NONE, 5.0);
    givenOrders(Set.of(iridium.getId()), first, second, items);

    Map<Bucket, Double> webGaps = webGaps(web.getMaterialDemandOverview());
    List<ExchangeDemandMaterialDto> lines = exchange.demand(MEMBER).materials();

    assertThat(webGaps)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                new Bucket(agricium.id(), 650), 72.5,
                new Bucket(agricium.id(), 0), 15.0,
                new Bucket(beryl.id(), 0), 0.0));
    assertThat(exchangeOpen(lines)).isEqualTo(positive(webGaps));
    assertThat(lines).noneMatch(l -> l.material().bt().equals(beryl.id().toString()));
    assertThat(lines)
        .filteredOn(l -> l.minQuality() == 650)
        .extracting(ExchangeDemandMaterialDto::source)
        .containsExactlyInAnyOrder(
            ExchangeDemandService.MATERIAL_ORDER, ExchangeDemandService.ITEM_ORDER);
  }

  @Test
  void theJobOrderGateWithholdsTheDemandOnBothSurfaces() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    MaterialDemandOverviewDto overview = web.getMaterialDemandOverview();
    ExchangeOrgDemandDto demand = exchange.demand(MEMBER);

    assertThat(overview.groups()).isEmpty();
    assertThat(demand.materials()).isEmpty();
    assertThat(demand.items()).isEmpty();
    assertThat(demand.reason()).isEqualTo(ExchangeOrgDemandDto.Reason.NOT_PERMITTED);
  }

  @Test
  void claimsReduceNeitherTheWebGapNorTheOrgDemand() {
    SpecialCommand logistics = specialCommand("SKL");
    JobOrder order = order(logistics, 5, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    requires(order, new MaterialRequirement(agricium, NONE, 50.0));
    booked(order, agricium, NONE, 10.0);
    when(materialClaimService.getClaimBucketsForOrders(anyList()))
        .thenReturn(
            Map.of(
                order.getId(),
                List.of(
                    new ClaimBucketDto(agricium, NONE.code(), NONE, 50.0, 30.0, 20.0, List.of()))));
    givenOrders(Set.of(logistics.getId()), order);

    MaterialDemandOverviewDto overview = web.getMaterialDemandOverview();
    Map<Bucket, Double> webGaps = webGaps(overview);

    assertThat(overview.groups().get(0).materials().get(0).claimedAmount()).isEqualTo(30.0);
    assertThat(webGaps)
        .containsExactlyInAnyOrderEntriesOf(Map.of(new Bucket(agricium.id(), 0), 40.0));
    assertThat(exchangeOpen(exchange.demand(MEMBER).materials())).isEqualTo(webGaps);
  }

  @Test
  void theOrgDemandOfTwoUnitsEqualsTheWebGroupsSummed() {
    Squadron iridium = squadron("IRI");
    Squadron nova = squadron("NOV");
    JobOrder first = order(iridium, 1, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    JobOrder second = order(nova, 2, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    requires(first, new MaterialRequirement(agricium, GOOD, 10.0));
    requires(
        second,
        new MaterialRequirement(agricium, GOOD, 7.0),
        new MaterialRequirement(beryl, NONE, 3.0));
    booked(first, agricium, GOOD, 2.5);
    booked(second, beryl, NONE, 3.0);
    givenOrders(Set.of(iridium.getId(), nova.getId()), first, second);

    MaterialDemandOverviewDto overview = web.getMaterialDemandOverview();
    Map<Bucket, Double> webGaps = webGaps(overview);

    assertThat(overview.groups()).hasSize(2);
    assertThat(webGaps)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(new Bucket(agricium.id(), 650), 14.5, new Bucket(beryl.id(), 0), 0.0));
    assertThat(exchangeOpen(exchange.demand(MEMBER).materials())).isEqualTo(positive(webGaps));
  }

  @Test
  void anOverBookedOrderOffsetsAnotherOrdersGapInTheSameUnitOnBothSurfaces() {
    Squadron iridium = squadron("IRI");
    JobOrder covered = order(iridium, 1, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    JobOrder lacking = order(iridium, 2, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    requires(covered, new MaterialRequirement(agricium, NONE, 10.0));
    requires(lacking, new MaterialRequirement(agricium, NONE, 20.0));
    booked(covered, agricium, NONE, 25.0);
    givenOrders(Set.of(iridium.getId()), covered, lacking);

    Map<Bucket, Double> webGaps = webGaps(web.getMaterialDemandOverview());

    assertThat(webGaps)
        .containsExactlyInAnyOrderEntriesOf(Map.of(new Bucket(agricium.id(), 0), 5.0));
    assertThat(exchangeOpen(exchange.demand(MEMBER).materials())).isEqualTo(webGaps);
  }

  @Test
  void pieceCountedDemandIsRoundedBeforeTheSubtractionOnBothSurfaces() {
    MaterialDto frames = pieceMaterial("Frame");
    Squadron iridium = squadron("IRI");
    JobOrder order = order(iridium, 1, JobOrderType.MATERIAL, JobOrderStatus.OPEN);
    requires(order, new MaterialRequirement(frames, NONE, 2.4));
    booked(order, frames, NONE, 1.6);
    givenOrders(Set.of(iridium.getId()), order);

    Map<Bucket, Double> webGaps = webGaps(web.getMaterialDemandOverview());

    assertThat(webGaps).containsExactlyInAnyOrderEntriesOf(Map.of(new Bucket(frames.id(), 0), 0.0));
    assertThat(exchange.demand(MEMBER).materials()).isEmpty();
  }

  /**
   * Hands both services the same orders: the exchange through the member's units, the web through a
   * scope over the same units.
   *
   * @param units the member's units
   * @param orders the open orders of those units
   */
  private void givenOrders(@NotNull Set<UUID> units, JobOrder... orders) {
    List<JobOrder> list = Arrays.asList(orders);
    when(membershipRepository.findOrgUnitIdsByUserId(MEMBER)).thenReturn(units);
    when(jobOrderRepository.findOpenForExchangeDemand(any(), eq(units))).thenReturn(list);
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, units));
    when(jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            any(), eq(false), eq(null), eq(units)))
        .thenReturn(list);
  }

  private void requires(@NotNull JobOrder order, MaterialRequirement... requirements) {
    when(requirementResolver.requirementsOf(order)).thenReturn(List.of(requirements));
  }

  private void booked(
      @NotNull JobOrder order,
      @NotNull MaterialDto material,
      @NotNull QualityTierDto tier,
      double amount) {
    bookedByOrder
        .computeIfAbsent(order.getId(), id -> new HashMap<>())
        .put(new TierBucket(material.id(), tier.id()), amount);
  }

  /**
   * Answers the stock index the way the allocator would for the stubbed figures: each requirement
   * gets the amount {@link #booked} recorded for its order, material and tier.
   *
   * @param orderId the order
   * @param requirements the order's requirements
   * @return the booked amount per requirement, aligned with the list
   */
  private double @NotNull [] bookedFor(
      @NotNull UUID orderId, @NotNull List<MaterialRequirement> requirements) {
    Map<TierBucket, Double> byBucket = bookedByOrder.getOrDefault(orderId, Map.of());
    double[] booked = new double[requirements.size()];
    for (int i = 0; i < requirements.size(); i++) {
      MaterialRequirement requirement = requirements.get(i);
      booked[i] =
          byBucket.getOrDefault(
              new TierBucket(requirement.material().id(), requirement.tier().id()), 0.0);
    }
    return booked;
  }

  /**
   * Reads the web's gap per bucket, summed over its org-unit groups, and checks each row's own gap.
   *
   * @param overview the web's Materialbedarf
   * @return {@code max(0, required - booked)} per material and quality floor
   */
  private static @NotNull Map<Bucket, Double> webGaps(@NotNull MaterialDemandOverviewDto overview) {
    Map<Bucket, Double> gaps = new TreeMap<>();
    for (MaterialDemandGroupDto group : overview.groups()) {
      for (MaterialDemandRowDto row : group.materials()) {
        double gap = Math.max(0.0, row.requiredAmount() - row.bookedAmount());
        assertThat(row.outstandingAmount()).isEqualTo(gap);
        gaps.merge(
            new Bucket(row.material().id(), row.qualityTier().minQuality()), gap, Double::sum);
      }
    }
    return gaps;
  }

  /**
   * Reads the exchange's open quantity per bucket, summed over its sources.
   *
   * @param lines the exchange's material lines
   * @return the open quantity per material and quality floor
   */
  private static @NotNull Map<Bucket, Double> exchangeOpen(
      @NotNull List<ExchangeDemandMaterialDto> lines) {
    Map<Bucket, Double> open = new TreeMap<>();
    for (ExchangeDemandMaterialDto line : lines) {
      open.merge(
          new Bucket(UUID.fromString(line.material().bt()), line.minQuality()),
          line.openQuantity().amount().doubleValue(),
          Double::sum);
    }
    return open;
  }

  private static @NotNull Map<Bucket, Double> positive(@NotNull Map<Bucket, Double> gaps) {
    return gaps.entrySet().stream()
        .filter(e -> e.getValue() > 0)
        .collect(
            Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Double::sum, TreeMap::new));
  }

  private static @NotNull JobOrder order(
      @Nullable OrgUnit responsible,
      int displayId,
      @NotNull JobOrderType type,
      @NotNull JobOrderStatus status) {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(displayId);
    order.setStatus(status);
    order.setType(type);
    order.setResponsibleOrgUnit(responsible);
    order.setItems(new LinkedHashSet<>());
    order.setMaterials(new LinkedHashSet<>());
    return order;
  }

  private static @NotNull Squadron squadron(@NotNull String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName(shorthand + " squadron");
    squadron.setShorthand(shorthand);
    return squadron;
  }

  private static @NotNull SpecialCommand specialCommand(@NotNull String shorthand) {
    SpecialCommand command = new SpecialCommand();
    command.setId(UUID.randomUUID());
    command.setName(shorthand + " command");
    command.setShorthand(shorthand);
    return command;
  }

  private static @NotNull MaterialDto material(@NotNull String name) {
    return material(name, "SCU");
  }

  private static @NotNull MaterialDto pieceMaterial(@NotNull String name) {
    return material(name, "PIECE");
  }

  private static @NotNull MaterialDto material(@NotNull String name, @NotNull String unit) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        unit,
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

  /**
   * One demand bucket as both surfaces can name it.
   *
   * @param materialId the material
   * @param floor the quality floor, {@code 0} for none
   */
  private record Bucket(@NotNull UUID materialId, int floor) implements Comparable<Bucket> {

    @Override
    public int compareTo(@NotNull Bucket other) {
      int byMaterial = materialId.compareTo(other.materialId);
      return byMaterial != 0 ? byMaterial : Integer.compare(floor, other.floor);
    }
  }

  /**
   * One stubbed stock bucket of an order.
   *
   * @param materialId the material
   * @param tierId the quality tier
   */
  private record TierBucket(@NotNull UUID materialId, @NotNull UUID tierId) {}
}
