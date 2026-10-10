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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.ScopePredicate;
import de.greluc.krt.profit.basetool.backend.support.QualityTierFixtures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the cross-order material-demand aggregation (REQ-ORDERS-034): that both order
 * kinds fold into the same bucket, that the grouping keys on the responsible org unit, that the
 * query is scoped and status-restricted, and that the coverage columns keep their distinct
 * meanings.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderMaterialDemandServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private JobOrderStockProjectionService jobOrderStockProjectionService;
  @Mock private JobOrderItemService jobOrderItemService;
  @Mock private MaterialClaimService materialClaimService;
  @Mock private MaterialMapper materialMapper;
  @Mock private SquadronMapper squadronMapper;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Spy private QualityTierMapper qualityTierMapper = Mappers.getMapper(QualityTierMapper.class);

  /**
   * Real, not mocked: these cases assert that a MATERIAL line and an ITEM order's blueprint-derived
   * requirement fold into one bucket.
   */
  @InjectMocks private JobOrderMaterialRequirementResolver materialRequirementResolver;

  private JobOrderMaterialDemandService service;

  /**
   * Stubbed batched stock lookup; each test decides what a bucket has linked to it, and an
   * unstubbed order books nothing.
   */
  private OrderLinkedStockIndex stockIndex;

  private Material titanium;
  private MaterialDto titaniumDto;

  @BeforeEach
  void setUp() {
    service =
        new JobOrderMaterialDemandService(
            jobOrderRepository,
            ownerScopeService,
            materialRequirementResolver,
            jobOrderStockProjectionService,
            materialClaimService,
            squadronMapper);
    stockIndex =
        mock(
            OrderLinkedStockIndex.class,
            invocation -> {
              if ("bookedFor".equals(invocation.getMethod().getName())) {
                List<?> requirements = invocation.getArgument(1);
                return new double[requirements == null ? 0 : requirements.size()];
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
    titanium = material("Titanium", QuantityType.SCU);
    titaniumDto = materialDto(titanium);
  }

  /**
   * Puts the service past its gate with an admin-wide scope and an empty stock/claim world, so a
   * test only has to declare the orders and the deviations it cares about.
   *
   * @param orders the orders the scoped query returns.
   */
  private void givenVisibleOrders(List<JobOrder> orders) {
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            any(), eq(true), eq(null), any()))
        .thenReturn(orders);
    when(jobOrderStockProjectionService.loadOrderLinkedStockIndex(any())).thenReturn(stockIndex);
    when(materialClaimService.getClaimBucketsForOrders(anyList())).thenReturn(Map.of());
  }

  private static Material material(String name, QuantityType quantityType) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setQuantityType(quantityType);
    return material;
  }

  private static MaterialDto materialDto(Material material) {
    return new MaterialDto(
        material.getId(),
        material.getName(),
        null,
        material.getQuantityType() == null ? null : material.getQuantityType().name(),
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

  private static Squadron squadron(String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName(shorthand + " squadron");
    squadron.setShorthand(shorthand);
    return squadron;
  }

  /**
   * Builds a MATERIAL order with a single material line.
   *
   * @param responsible the processing org unit.
   * @param displayId the human-readable order number.
   * @param material the required material.
   * @param tier the line's quality tier.
   * @param amount the outstanding required amount.
   * @return the order.
   */
  private static JobOrder materialOrder(
      OrgUnit responsible, int displayId, Material material, QualityTier tier, double amount) {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(displayId);
    order.setStatus(JobOrderStatus.OPEN);
    order.setType(JobOrderType.MATERIAL);
    order.setResponsibleOrgUnit(responsible);
    order.setMaterials(new LinkedHashSet<>(List.of(line(material, tier, amount))));
    return order;
  }

  /**
   * Builds one material line of a MATERIAL order.
   *
   * @param material the required material.
   * @param tier the line's quality tier.
   * @param amount the outstanding required amount.
   * @return the line.
   */
  private static JobOrderMaterial line(Material material, QualityTier tier, double amount) {
    JobOrderMaterial line = new JobOrderMaterial();
    line.setMaterial(material);
    line.setQualityTier(tier);
    line.setAmount(amount);
    return line;
  }

  /**
   * Builds an ITEM order; its material requirements come from the stubbed {@code
   * aggregateMaterials} rather than from entity state, exactly as the production path derives them
   * from blueprints.
   *
   * @param responsible the processing org unit.
   * @param displayId the human-readable order number.
   * @return the order.
   */
  private static JobOrder itemOrder(OrgUnit responsible, int displayId) {
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    order.setDisplayId(displayId);
    order.setStatus(JobOrderStatus.IN_PROGRESS);
    order.setType(JobOrderType.ITEM);
    order.setResponsibleOrgUnit(responsible);
    order.setItems(new LinkedHashSet<>(List.<JobOrderItem>of()));
    return order;
  }

  @Test
  @DisplayName("A MATERIAL and an ITEM order of one unit fold into a single material bucket")
  void aggregatesBothOrderKindsIntoOneBucket() {
    Squadron iridium = squadron("IRI");
    JobOrder matOrder = materialOrder(iridium, 1, titanium, QualityTierFixtures.good(), 400.0);
    JobOrder itmOrder = itemOrder(iridium, 2);
    givenVisibleOrders(List.of(matOrder, itmOrder));
    when(materialMapper.toDto(titanium)).thenReturn(titaniumDto);
    when(squadronMapper.orgUnitToReferenceDto(iridium))
        .thenReturn(new SquadronReferenceDto(iridium.getId(), iridium.getName(), "IRI"));
    when(jobOrderItemService.aggregateMaterials(itmOrder))
        .thenReturn(
            List.of(
                new AggregatedMaterialDto(
                    titaniumDto,
                    "GOOD",
                    QualityTierFixtures.goodDto(),
                    600.0,
                    null,
                    List.of(),
                    null)));
    when(stockIndex.bookedFor(eq(matOrder.getId()), anyList())).thenReturn(new double[] {100.0});
    when(stockIndex.bookedFor(eq(itmOrder.getId()), anyList())).thenReturn(new double[] {50.0});

    MaterialDemandOverviewDto overview = service.getMaterialDemandOverview();

    assertThat(overview.groups()).hasSize(1);
    MaterialDemandGroupDto group = overview.groups().get(0);
    assertThat(group.orgUnit().shorthand()).isEqualTo("IRI");
    assertThat(group.materials()).hasSize(1);

    MaterialDemandRowDto row = group.materials().get(0);
    assertThat(row.qualityRequirement()).isEqualTo("GOOD");
    assertThat(row.qualityTier().id()).isEqualTo(QualityTierFixtures.GOOD_ID);
    assertThat(row.requiredAmount()).isEqualTo(1000.0);
    assertThat(row.bookedAmount()).isEqualTo(150.0);
    assertThat(row.outstandingAmount()).isEqualTo(850.0);
    assertThat(row.orders()).extracting(share -> share.displayId()).containsExactly(1, 2);
  }

  @Test
  @DisplayName("Demand is grouped by the responsible, not the requesting, org unit")
  void groupsByResponsibleOrgUnit() {
    Squadron iridium = squadron("IRI");
    Squadron nova = squadron("NOV");
    JobOrder first = materialOrder(iridium, 1, titanium, QualityTierFixtures.none(), 10.0);
    JobOrder second = materialOrder(nova, 2, titanium, QualityTierFixtures.none(), 20.0);
    givenVisibleOrders(List.of(first, second));
    when(materialMapper.toDto(titanium)).thenReturn(titaniumDto);
    when(squadronMapper.orgUnitToReferenceDto(iridium))
        .thenReturn(new SquadronReferenceDto(iridium.getId(), iridium.getName(), "IRI"));
    when(squadronMapper.orgUnitToReferenceDto(nova))
        .thenReturn(new SquadronReferenceDto(nova.getId(), nova.getName(), "NOV"));

    MaterialDemandOverviewDto overview = service.getMaterialDemandOverview();

    assertThat(overview.groups()).hasSize(2);
    assertThat(overview.groups())
        .extracting(group -> group.orgUnit().shorthand())
        .containsExactly("IRI", "NOV");
    assertThat(overview.groups().get(0).materials().get(0).requiredAmount()).isEqualTo(10.0);
    assertThat(overview.groups().get(1).materials().get(0).requiredAmount()).isEqualTo(20.0);
  }

  @Test
  @DisplayName("The query asks only for OPEN + IN_PROGRESS and carries the caller's scope")
  void queriesOnlyNonTerminalOrdersWithinTheCallersScope() {
    UUID activeOrgUnit = UUID.randomUUID();
    Set<UUID> memberships = Set.of(UUID.randomUUID());
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, activeOrgUnit, memberships));
    when(jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            any(), eq(false), eq(activeOrgUnit), eq(memberships)))
        .thenReturn(List.of());

    MaterialDemandOverviewDto overview = service.getMaterialDemandOverview();

    assertThat(overview.groups()).isEmpty();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Collection<JobOrderStatus>> statuses =
        ArgumentCaptor.forClass(java.util.Collection.class);
    verify(jobOrderRepository)
        .findScopedOrdersWithMaterialRequirements(
            statuses.capture(), eq(false), eq(activeOrgUnit), eq(memberships));
    assertThat(statuses.getValue())
        .containsExactlyInAnyOrder(JobOrderStatus.OPEN, JobOrderStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("A caller outside the order workflow gets an empty overview and no query")
  void nonProfitCallerGetsEmptyOverview() {
    when(ownerScopeService.canViewJobOrders()).thenReturn(false);

    MaterialDemandOverviewDto overview = service.getMaterialDemandOverview();

    assertThat(overview.groups()).isEmpty();
    verifyNoInteractions(jobOrderRepository);
    verify(jobOrderStockProjectionService, never()).loadOrderLinkedStockIndex(any());
  }

  @Test
  @DisplayName("Claims are reported but never reduce the gathering gap")
  void claimsAreReportedSeparatelyAndDoNotShrinkTheOutstandingAmount() {
    SpecialCommand skCommand = new SpecialCommand();
    skCommand.setId(UUID.randomUUID());
    skCommand.setName("Spezialkommando Logistik");
    skCommand.setShorthand("SKL");
    JobOrder order = materialOrder(skCommand, 5, titanium, QualityTierFixtures.none(), 500.0);

    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            any(), eq(true), eq(null), any()))
        .thenReturn(List.of(order));
    when(jobOrderStockProjectionService.loadOrderLinkedStockIndex(any())).thenReturn(stockIndex);
    when(materialClaimService.getClaimBucketsForOrders(anyList()))
        .thenReturn(
            Map.of(
                order.getId(),
                List.of(
                    new ClaimBucketDto(
                        titaniumDto,
                        "NONE",
                        QualityTierFixtures.noneDto(),
                        500.0,
                        300.0,
                        200.0,
                        List.of()))));
    when(materialMapper.toDto(titanium)).thenReturn(titaniumDto);
    when(squadronMapper.orgUnitToReferenceDto(skCommand))
        .thenReturn(new SquadronReferenceDto(skCommand.getId(), skCommand.getName(), "SKL"));
    when(stockIndex.bookedFor(eq(order.getId()), anyList())).thenReturn(new double[] {50.0});

    MaterialDemandRowDto row =
        service.getMaterialDemandOverview().groups().get(0).materials().get(0);

    assertThat(row.claimedAmount()).isEqualTo(300.0);
    assertThat(row.outstandingAmount()).isEqualTo(450.0);
    assertThat(row.orders().get(0).claimedAmount()).isEqualTo(300.0);
  }

  @Test
  @DisplayName("A fully covered bucket reports a zero gap rather than a negative one")
  void outstandingIsFlooredAtZeroWhenStockExceedsDemand() {
    Squadron iridium = squadron("IRI");
    JobOrder order = materialOrder(iridium, 9, titanium, QualityTierFixtures.none(), 100.0);
    givenVisibleOrders(List.of(order));
    when(materialMapper.toDto(titanium)).thenReturn(titaniumDto);
    when(squadronMapper.orgUnitToReferenceDto(iridium))
        .thenReturn(new SquadronReferenceDto(iridium.getId(), iridium.getName(), "IRI"));
    when(stockIndex.bookedFor(eq(order.getId()), anyList())).thenReturn(new double[] {180.0});

    MaterialDemandRowDto row =
        service.getMaterialDemandOverview().groups().get(0).materials().get(0);

    assertThat(row.bookedAmount()).isEqualTo(180.0);
    assertThat(row.outstandingAmount()).isZero();
  }

  @Test
  @DisplayName("A PIECE material's aggregated amounts stay whole units")
  void pieceMaterialAmountsAreRoundedToWholeUnits() {
    Squadron iridium = squadron("IRI");
    Material core = material("Quantum Core", QuantityType.PIECE);
    MaterialDto coreDto = materialDto(core);
    JobOrder first = materialOrder(iridium, 1, core, QualityTierFixtures.none(), 2.4);
    JobOrder second = materialOrder(iridium, 2, core, QualityTierFixtures.none(), 3.4);
    givenVisibleOrders(List.of(first, second));
    when(materialMapper.toDto(core)).thenReturn(coreDto);
    when(squadronMapper.orgUnitToReferenceDto(iridium))
        .thenReturn(new SquadronReferenceDto(iridium.getId(), iridium.getName(), "IRI"));

    MaterialDemandRowDto row =
        service.getMaterialDemandOverview().groups().get(0).materials().get(0);

    assertThat(row.requiredAmount()).isEqualTo(6.0);
  }

  @Test
  @DisplayName("One linked stock row is counted once across an order's GOOD and NONE buckets")
  void linkedStockIsAllocatedAcrossQualityBucketsWithoutDoubleCounting() {
    Squadron iridium = squadron("IRI");
    Material stileron = material("Stileron", QuantityType.SCU);
    MaterialDto stileronDto = materialDto(stileron);
    JobOrder order = materialOrder(iridium, 7, stileron, QualityTierFixtures.good(), 0.1);
    order.getMaterials().add(line(stileron, QualityTierFixtures.none(), 2.64));
    when(ownerScopeService.canViewJobOrders()).thenReturn(true);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(jobOrderRepository.findScopedOrdersWithMaterialRequirements(
            any(), eq(true), eq(null), any()))
        .thenReturn(List.of(order));
    when(inventoryItemRepository.findMaterialStockRowsByJobOrderIds(List.of(order.getId())))
        .thenReturn(
            List.of(
                new JobOrderMaterialStockRow(
                    order.getId(), UUID.randomUUID(), stileron.getId(), 681, 2.64)));
    OrderLinkedStockIndex realIndex =
        new JobOrderStockProjectionService(inventoryItemRepository, null, null, null, null)
            .loadOrderLinkedStockIndex(List.of(order.getId()));
    when(jobOrderStockProjectionService.loadOrderLinkedStockIndex(any())).thenReturn(realIndex);
    when(materialClaimService.getClaimBucketsForOrders(anyList())).thenReturn(Map.of());
    when(materialMapper.toDto(stileron)).thenReturn(stileronDto);
    when(squadronMapper.orgUnitToReferenceDto(iridium))
        .thenReturn(new SquadronReferenceDto(iridium.getId(), iridium.getName(), "IRI"));

    List<MaterialDemandRowDto> rows =
        service.getMaterialDemandOverview().groups().get(0).materials();

    assertThat(rows)
        .extracting(MaterialDemandRowDto::qualityRequirement)
        .containsExactly("GOOD", "NONE");
    MaterialDemandRowDto good = rows.get(0);
    assertThat(good.requiredAmount()).isEqualTo(0.1);
    assertThat(good.bookedAmount()).isEqualTo(0.1);
    assertThat(good.outstandingAmount()).isZero();
    MaterialDemandRowDto none = rows.get(1);
    assertThat(none.requiredAmount()).isEqualTo(2.64);
    assertThat(none.bookedAmount()).isEqualTo(2.54);
    assertThat(none.outstandingAmount()).isEqualTo(0.1);
  }
}
