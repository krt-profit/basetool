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

import de.greluc.krt.profit.basetool.backend.mapper.JobOrderItemHandoverMapper;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.LinkedStockAttributionDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.service.JobOrderMaterialRequirementResolver.MaterialRequirement;
import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Allocation;
import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Demand;
import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Supply;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Projects managed {@link JobOrder}s into {@link JobOrderDto}s with per-bucket order-linked stock
 * and, for SK-responsible orders, per-squadron material claims. Every stock figure comes from one
 * {@link QualityBucketAllocator} run per order and material, so a stock row counts toward exactly
 * one quality bucket (REQ-ORDERS-037). Read-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrderStockProjectionService {

  /** Loads the order-linked stock rows. */
  private final InventoryItemRepository inventoryItemRepository;

  /** Supplies the per-order / page-batched SK material-claim view. */
  private final ClaimBucketSource claimBucketSource;

  /** Maps the base order + material rows to their DTOs. */
  private final JobOrderMapper jobOrderMapper;

  /** Supplies item-order aggregated materials + item DTOs. */
  private final JobOrderItemService jobOrderItemService;

  /** Maps item-order handover records to DTOs. */
  private final JobOrderItemHandoverMapper jobOrderItemHandoverMapper;

  /**
   * Projects a single order with its per-bucket stock and, for an SK order, its claim view.
   *
   * @param jobOrder the managed order to project.
   * @return the assembled order DTO.
   */
  @NotNull
  public JobOrderDto mapToDtoWithStock(@NotNull JobOrder jobOrder) {
    return assembleDto(
        jobOrder,
        loadOrderLinkedStockIndex(List.of(jobOrder.getId())),
        claimBucketSource::getClaimBucketsForOrder);
  }

  /**
   * Projects a page of orders, loading all linked stock and all SK claims in one query each
   * (REQ-DATA-003).
   *
   * @param page the scoped page of managed orders
   * @return the page mapped to stock- and claim-enriched DTOs
   */
  public Page<JobOrderDto> mapPageWithStock(@NotNull Page<JobOrder> page) {
    List<JobOrder> orders = page.getContent();
    OrderLinkedStockIndex stockIndex =
        loadOrderLinkedStockIndex(orders.stream().map(JobOrder::getId).toList());
    Map<UUID, List<ClaimBucketDto>> claimsByOrder =
        claimBucketSource.getClaimBucketsForOrders(
            orders.stream()
                .filter(JobOrderStockProjectionService::isSpecialCommandResponsible)
                .toList());
    ClaimResolver claimResolver = order -> claimsByOrder.getOrDefault(order.getId(), List.of());
    jobOrderMapper.primeAssignees(orders);
    return page.map(o -> assembleDto(o, stockIndex, claimResolver));
  }

  /**
   * Loads the order-linked material stock of many orders in one query as a reusable lookup
   * (REQ-DATA-003).
   *
   * @param orderIds the orders whose linked stock to index; empty yields an empty index without a
   *     query
   * @return the batched lookup, never {@code null}
   */
  @NotNull
  public OrderLinkedStockIndex loadOrderLinkedStockIndex(@NotNull Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return new OrderLinkedStockIndex(Map.of());
    }
    return new OrderLinkedStockIndex(
        inventoryItemRepository.findMaterialStockRowsByJobOrderIds(orderIds).stream()
            .collect(
                Collectors.groupingBy(
                    JobOrderMaterialStockRow::jobOrderId,
                    Collectors.groupingBy(JobOrderMaterialStockRow::materialId))));
  }

  /**
   * Reports, per linked stock row of one material, which quality buckets it counts toward
   * (REQ-ORDERS-037). Rows below every floor appear with no bucket.
   *
   * @param jobOrder the managed order
   * @param requirements the order's requirements, from {@link
   *     JobOrderMaterialRequirementResolver#requirementsOf(JobOrder)}
   * @param materialId the material
   * @return one entry per row and bucket, plus one entry without a bucket per unattributed row
   */
  @NotNull
  public List<LinkedStockAttributionDto> attributionFor(
      @NotNull JobOrder jobOrder,
      @NotNull List<MaterialRequirement> requirements,
      @NotNull UUID materialId) {
    OrderLinkedStockIndex index = loadOrderLinkedStockIndex(List.of(jobOrder.getId()));
    List<MaterialRequirement> ofMaterial =
        requirements.stream().filter(r -> r.material().id().equals(materialId)).toList();
    Allocation<Integer> allocation =
        index.allocate(jobOrder.getId(), materialId, demandsOf(ofMaterial));
    List<LinkedStockAttributionDto> result = new ArrayList<>();
    for (Supply supply : index.suppliesFor(jobOrder.getId(), materialId)) {
      Map<Integer, Double> byDemand = allocation.byRow().getOrDefault(supply.rowId(), Map.of());
      Map<UUID, Double> byTier = new LinkedHashMap<>();
      byDemand.forEach(
          (demandIndex, amount) ->
              byTier.merge(ofMaterial.get(demandIndex).tier().id(), amount, Double::sum));
      double attributed = byTier.values().stream().mapToDouble(Double::doubleValue).sum();
      byTier.forEach(
          (tierId, amount) ->
              result.add(new LinkedStockAttributionDto(supply.rowId(), tierId, round3(amount))));
      double rest = Math.max(0.0, supply.amount() - attributed);
      if (rest > 1e-9) {
        result.add(new LinkedStockAttributionDto(supply.rowId(), null, round3(rest)));
      }
    }
    return result;
  }

  /**
   * Builds the allocator demands of one material's requirements, keyed by their list index.
   *
   * @param requirements the requirements of one order and one material
   * @return the demands, in list order
   */
  @NotNull
  static List<Demand<Integer>> demandsOf(@NotNull List<MaterialRequirement> requirements) {
    List<Demand<Integer>> demands = new ArrayList<>();
    for (int i = 0; i < requirements.size(); i++) {
      MaterialRequirement requirement = requirements.get(i);
      demands.add(new Demand<>(i, requirement.tier().minQuality(), requirement.requiredAmount()));
    }
    return demands;
  }

  /**
   * Assembles the order DTO from the batched stock index and a pluggable claim resolver.
   *
   * @param jobOrder the managed order to project
   * @param stockIndex the order-linked stock of at least this order
   * @param claimResolver resolves the SK claim view of one order ({@code List.of()} for non-SK)
   * @return the order DTO with per-bucket stock and, for SK orders, claims
   */
  @NotNull
  private JobOrderDto assembleDto(
      JobOrder jobOrder, OrderLinkedStockIndex stockIndex, ClaimResolver claimResolver) {
    JobOrderDto baseDto = jobOrderMapper.toDto(jobOrder);

    Map<String, ClaimBucketDto> claimByBucket =
        isSpecialCommandResponsible(jobOrder)
            ? claimResolver.claimsFor(jobOrder).stream()
                .collect(
                    Collectors.toMap(
                        b -> bucketKey(b.material().id(), b.qualityTier().id()), b -> b))
            : Map.of();

    List<JobOrderMaterialDto> baseMaterials = baseDto.materials();
    double[] booked =
        stockIndex.bookedFor(
            jobOrder.getId(),
            baseMaterials.stream()
                .map(
                    m ->
                        new MaterialRequirement(
                            m.material(), m.qualityTier(), m.amount() == null ? 0.0 : m.amount()))
                .toList());
    List<JobOrderMaterialDto> updatedMaterials = new ArrayList<>();
    for (int i = 0; i < baseMaterials.size(); i++) {
      JobOrderMaterialDto matDto = baseMaterials.get(i);
      ClaimBucketDto bucket =
          claimByBucket.get(bucketKey(matDto.material().id(), matDto.qualityTier().id()));
      updatedMaterials.add(
          new JobOrderMaterialDto(
              matDto.id(),
              matDto.material(),
              matDto.minQuality(),
              matDto.qualityTier(),
              matDto.amount(),
              round3(booked[i]),
              bucket != null ? bucket.claims() : List.of(),
              bucket != null ? bucket.openRemaining() : null,
              matDto.version()));
    }

    boolean isItem = jobOrder.getType() == JobOrderType.ITEM;
    List<JobOrderItemDto> items = isItem ? jobOrderItemService.toItemDtos(jobOrder) : List.of();
    List<AggregatedMaterialDto> aggregatedMaterials =
        isItem ? enrichAggregatedWithClaims(jobOrder, claimByBucket, stockIndex) : List.of();
    List<JobOrderItemHandoverDto> itemHandovers =
        isItem
            ? jobOrder.getItemHandovers().stream().map(jobOrderItemHandoverMapper::toDto).toList()
            : List.of();

    return new JobOrderDto(
        baseDto.id(),
        baseDto.displayId(),
        baseDto.responsibleOrgUnit(),
        baseDto.requestingOrgUnit(),
        baseDto.handle(),
        baseDto.comment(),
        baseDto.priority(),
        baseDto.status(),
        baseDto.type(),
        baseDto.countBlueprintsWithVariants(),
        updatedMaterials,
        items,
        aggregatedMaterials,
        baseDto.assignees(),
        baseDto.handovers(),
        itemHandovers,
        baseDto.createdAt(),
        baseDto.version(),
        baseDto.canEdit(),
        false);
  }

  /**
   * Enriches the item order's aggregated-material rows with the stock attributed to each bucket
   * and, for SK orders, with claims and open amount; non-SK rows keep empty claims and a {@code
   * null} open amount.
   *
   * @param jobOrder the item order
   * @param claimByBucket the SK claim view keyed by {@link #bucketKey}, or empty for non-SK orders
   * @param stockIndex the order-linked stock of at least this order
   * @return the stock- and claim-enriched rows
   */
  private List<AggregatedMaterialDto> enrichAggregatedWithClaims(
      JobOrder jobOrder,
      Map<String, ClaimBucketDto> claimByBucket,
      OrderLinkedStockIndex stockIndex) {
    List<AggregatedMaterialDto> aggregated = jobOrderItemService.aggregateMaterials(jobOrder);
    double[] booked =
        stockIndex.bookedFor(
            jobOrder.getId(),
            aggregated.stream()
                .map(
                    agg ->
                        new MaterialRequirement(
                            agg.material(),
                            agg.qualityTier(),
                            agg.totalQuantity() == null ? 0.0 : agg.totalQuantity()))
                .toList());
    List<AggregatedMaterialDto> result = new ArrayList<>();
    for (int i = 0; i < aggregated.size(); i++) {
      AggregatedMaterialDto agg = aggregated.get(i);
      ClaimBucketDto bucket =
          claimByBucket.get(bucketKey(agg.material().id(), agg.qualityTier().id()));
      result.add(
          new AggregatedMaterialDto(
              agg.material(),
              agg.qualityRequirement(),
              agg.qualityTier(),
              agg.totalQuantity(),
              round3(booked[i]),
              bucket != null ? bucket.claims() : agg.claims(),
              bucket != null ? bucket.openRemaining() : agg.openAmount()));
    }
    return result;
  }

  /**
   * A pre-loaded, order-batched view of job-order-linked material inventory that distributes each
   * order's stock across its quality buckets in memory. Obtained from {@link
   * #loadOrderLinkedStockIndex(Collection)}; the backing index is never handed out.
   */
  public static final class OrderLinkedStockIndex {

    /** Order id &rarr; material id &rarr; the linked material inventory rows. */
    private final Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> rowsByOrderAndMaterial;

    /**
     * Wraps an already-loaded stock index.
     *
     * @param rowsByOrderAndMaterial the loaded index.
     */
    private OrderLinkedStockIndex(
        Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> rowsByOrderAndMaterial) {
      this.rowsByOrderAndMaterial = rowsByOrderAndMaterial;
    }

    /**
     * Returns the stock rows of one order and material as allocator supplies.
     *
     * @param jobOrderId the order
     * @param materialId the material
     * @return the supplies, empty when nothing is linked
     */
    @NotNull
    public List<Supply> suppliesFor(@NotNull UUID jobOrderId, @NotNull UUID materialId) {
      List<JobOrderMaterialStockRow> rows =
          rowsByOrderAndMaterial.getOrDefault(jobOrderId, Map.of()).get(materialId);
      if (rows == null) {
        return List.of();
      }
      Map<UUID, Supply> byRow = new LinkedHashMap<>();
      for (JobOrderMaterialStockRow row : rows) {
        double amount = row.amount() == null ? 0.0 : row.amount();
        byRow.merge(
            row.inventoryItemId(),
            new Supply(row.inventoryItemId(), row.quality(), amount),
            (a, b) -> new Supply(a.rowId(), a.quality(), a.amount() + b.amount()));
      }
      return List.copyOf(byRow.values());
    }

    /**
     * Distributes one order's stock of one material across the given demands.
     *
     * @param jobOrderId the order
     * @param materialId the material
     * @param demands the order's demands for that material
     * @param <K> the demand key type
     * @return the distribution
     */
    @NotNull
    public <K> Allocation<K> allocate(
        @NotNull UUID jobOrderId, @NotNull UUID materialId, @NotNull List<Demand<K>> demands) {
      return QualityBucketAllocator.allocate(demands, suppliesFor(jobOrderId, materialId));
    }

    /**
     * Returns the stock attributed to each requirement of one order, unrounded.
     *
     * @param jobOrderId the order
     * @param requirements the order's requirements, any materials, any order
     * @return the attributed amount per requirement, aligned with the list
     */
    public double @NotNull [] bookedFor(
        @NotNull UUID jobOrderId, @NotNull List<MaterialRequirement> requirements) {
      double[] booked = new double[requirements.size()];
      Map<UUID, List<Integer>> indexesByMaterial = new LinkedHashMap<>();
      for (int i = 0; i < requirements.size(); i++) {
        indexesByMaterial
            .computeIfAbsent(requirements.get(i).material().id(), unused -> new ArrayList<>())
            .add(i);
      }
      indexesByMaterial.forEach(
          (materialId, indexes) -> {
            List<Demand<Integer>> demands = new ArrayList<>();
            for (int index : indexes) {
              MaterialRequirement requirement = requirements.get(index);
              demands.add(
                  new Demand<>(
                      index, requirement.tier().minQuality(), requirement.requiredAmount()));
            }
            Allocation<Integer> allocation = allocate(jobOrderId, materialId, demands);
            for (int index : indexes) {
              booked[index] = allocation.attributedTo(index);
            }
          });
      return booked;
    }
  }

  /**
   * Resolves the SK claim view of one order. The single-order path backs it with the per-order
   * claim query; the paged list backs it with the page-batched claim lookup (REQ-DATA-003). Only
   * invoked for SK-responsible orders.
   */
  @FunctionalInterface
  private interface ClaimResolver {
    /**
     * Returns the per-bucket claim view of {@code order}.
     *
     * @param order the SK order whose claim buckets to return.
     * @return the claim buckets, never {@code null}.
     */
    List<ClaimBucketDto> claimsFor(JobOrder order);
  }

  /**
   * Returns whether the order is responsible to a Spezialkommando, the only orders with material
   * claims.
   *
   * @param jobOrder the order
   * @return whether the order is a public SK order
   */
  private static boolean isSpecialCommandResponsible(@NotNull JobOrder jobOrder) {
    return jobOrder.getResponsibleOrgUnit() != null
        && jobOrder.getResponsibleOrgUnit().getKind() == OrgUnitKind.SPECIAL_COMMAND;
  }

  /**
   * Builds the composite key identifying a material bucket ({@code materialId|tierId}) used to join
   * claim buckets onto the material / aggregated rows.
   *
   * @param materialId the material id.
   * @param qualityTierId the bucket's quality tier id.
   * @return the composite bucket key.
   */
  @NotNull
  private static String bucketKey(UUID materialId, UUID qualityTierId) {
    return materialId + "|" + qualityTierId;
  }

  /**
   * Rounds an attributed amount to SCU scale, removing floating-point noise.
   *
   * @param value the raw amount
   * @return the amount rounded to three decimals
   */
  private static double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }
}
