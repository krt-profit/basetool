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
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Projects managed {@link JobOrder}s into {@link JobOrderDto}s with per-bucket order-linked stock
 * and, for SK-responsible orders, per-squadron material claims. Read-only; single orders and paged
 * lists share one assembly in {@link #mapToDtoWithStock(JobOrder, StockResolver, ClaimResolver)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrderStockProjectionService {

  /**
   * Inventory quality floor a {@code GOOD} aggregated bucket sums stock at or above (650+ =
   * refining-grade); a {@code NONE} bucket imposes no floor. Mirrors the MATERIAL requirement's
   * stored {@code minQuality} so item-order collection progress is computed the same way.
   */
  private static final int GOOD_QUALITY_FLOOR = 650;

  /** Sums order-linked inventory stock (per-order query and page-batched index). */
  private final InventoryItemRepository inventoryItemRepository;

  /** Supplies the per-order / page-batched SK material-claim view. */
  private final MaterialClaimService materialClaimService;

  /** Maps the base order + material rows to their DTOs. */
  private final JobOrderMapper jobOrderMapper;

  /** Supplies item-order aggregated materials + item DTOs. */
  private final JobOrderItemService jobOrderItemService;

  /** Maps item-order handover records to DTOs. */
  private final JobOrderItemHandoverMapper jobOrderItemHandoverMapper;

  /**
   * Projects a single order with its per-bucket stock (per-order {@code SUM} queries) and, for an
   * SK order, its per-order claim view.
   *
   * @param jobOrder the managed order to project.
   * @return the assembled order DTO.
   */
  @NotNull
  public JobOrderDto mapToDtoWithStock(JobOrder jobOrder) {
    StockResolver stockResolver =
        (orderId, materialId, floor) -> {
          Double stock =
              inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
                  materialId, orderId, floor);
          return stock != null ? stock : 0.0;
        };
    return mapToDtoWithStock(
        jobOrder, stockResolver, order -> materialClaimService.getClaimBucketsForOrder(order));
  }

  /**
   * Assembles the order DTO from pluggable stock and claim resolvers, backed by per-order queries
   * on the single-order path and by page-batched lookups on the list path (REQ-DATA-003).
   *
   * @param jobOrder the managed order to project
   * @param stockResolver resolves the order-linked stock of one material at a quality floor
   * @param claimResolver resolves the SK claim view of one order ({@code List.of()} for non-SK)
   * @return the order DTO with per-bucket stock and, for SK orders, claims
   */
  @NotNull
  private JobOrderDto mapToDtoWithStock(
      JobOrder jobOrder, StockResolver stockResolver, ClaimResolver claimResolver) {
    JobOrderDto baseDto = jobOrderMapper.toDto(jobOrder);

    Map<String, ClaimBucketDto> claimByBucket =
        isSpecialCommandResponsible(jobOrder)
            ? claimResolver.claimsFor(jobOrder).stream()
                .collect(
                    Collectors.toMap(
                        b -> bucketKey(b.material().id(), b.qualityRequirement().name()), b -> b))
            : Map.of();

    List<JobOrderMaterialDto> updatedMaterials =
        baseDto.materials().stream()
            .map(
                matDto -> {
                  double stock =
                      stockResolver.stockFor(
                          jobOrder.getId(), matDto.material().id(), matDto.minQuality());
                  log.debug(
                      "Stock for job order #{} (ID: {}), material {}: {} / required: {} (min"
                          + " quality: {})",
                      jobOrder.getDisplayId(),
                      jobOrder.getId(),
                      matDto.material().name(),
                      stock,
                      matDto.amount(),
                      matDto.minQuality());
                  String qualityName =
                      matDto.minQuality() != null
                          ? QualityRequirement.GOOD.name()
                          : QualityRequirement.NONE.name();
                  ClaimBucketDto bucket =
                      claimByBucket.get(bucketKey(matDto.material().id(), qualityName));
                  return new JobOrderMaterialDto(
                      matDto.id(),
                      matDto.material(),
                      matDto.minQuality(),
                      matDto.amount(),
                      stock,
                      bucket != null ? bucket.claims() : List.of(),
                      bucket != null ? bucket.openRemaining() : null,
                      matDto.version());
                })
            .toList();

    boolean isItem = jobOrder.getType() == JobOrderType.ITEM;
    List<JobOrderItemDto> items = isItem ? jobOrderItemService.toItemDtos(jobOrder) : List.of();
    List<AggregatedMaterialDto> aggregatedMaterials =
        isItem ? enrichAggregatedWithClaims(jobOrder, claimByBucket, stockResolver) : List.of();
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
   * Projects a page of orders, loading all linked stock and all SK claims in one query each and
   * summing buckets in memory (REQ-DATA-003).
   *
   * @param page the scoped page of managed orders
   * @return the page mapped to stock- and claim-enriched DTOs
   */
  public Page<JobOrderDto> mapPageWithStock(@NotNull Page<JobOrder> page) {
    List<JobOrder> orders = page.getContent();
    OrderLinkedStockIndex stockIndex =
        loadOrderLinkedStockIndex(orders.stream().map(JobOrder::getId).toList());
    Map<UUID, List<ClaimBucketDto>> claimsByOrder =
        materialClaimService.getClaimBucketsForOrders(
            orders.stream()
                .filter(JobOrderStockProjectionService::isSpecialCommandResponsible)
                .toList());
    StockResolver stockResolver = stockIndex::stockFor;
    ClaimResolver claimResolver = order -> claimsByOrder.getOrDefault(order.getId(), List.of());
    jobOrderMapper.primeAssignees(orders);
    return page.map(o -> mapToDtoWithStock(o, stockResolver, claimResolver));
  }

  /**
   * Loads the order-linked material stock of many orders in one query as a reusable lookup
   * (REQ-DATA-003), with the same floor semantics as the per-order sum.
   *
   * @param orderIds the orders whose linked stock to index; empty yields an index answering {@code
   *     0.0} without a query
   * @return the batched lookup, never {@code null}
   */
  @NotNull
  public OrderLinkedStockIndex loadOrderLinkedStockIndex(Collection<UUID> orderIds) {
    return new OrderLinkedStockIndex(loadStockIndex(orderIds));
  }

  /**
   * Maps a bucket's quality requirement to its stock-summing floor: {@code GOOD} sums from {@value
   * #GOOD_QUALITY_FLOOR}, {@code NONE} has no floor.
   *
   * @param qualityRequirement the bucket's quality requirement
   * @return the minimum quality to sum at, or {@code null} for no floor
   */
  @Nullable
  public static Integer qualityFloorFor(QualityRequirement qualityRequirement) {
    return qualityRequirement == QualityRequirement.GOOD ? GOOD_QUALITY_FLOOR : null;
  }

  /**
   * A pre-loaded, order-batched view of job-order-linked material inventory that answers "how much
   * of material M is linked to order O at or above quality floor Q" purely in memory. Obtained from
   * {@link #loadOrderLinkedStockIndex(Collection)}; the backing index is never handed out, so the
   * lookup cannot be mutated after it has been built.
   */
  public static final class OrderLinkedStockIndex {

    /** Order id &rarr; material id &rarr; the linked material inventory rows of that bucket. */
    private final Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> rowsByOrderAndMaterial;

    /**
     * Wraps an already-loaded stock index; private so an instance can only originate from {@link
     * #loadOrderLinkedStockIndex(Collection)} and always reflects one batched query.
     *
     * @param rowsByOrderAndMaterial the loaded index.
     */
    private OrderLinkedStockIndex(
        Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> rowsByOrderAndMaterial) {
      this.rowsByOrderAndMaterial = rowsByOrderAndMaterial;
    }

    /**
     * Sums the linked stock of one order and material at a quality floor, in memory, with the same
     * semantics as {@code sumAmountByMaterialAndJobOrderAndMinQuality}.
     *
     * @param jobOrderId the order the stock is linked to
     * @param materialId the material to sum
     * @param qualityFloor the minimum quality, or {@code null} for no floor
     * @return the summed amount; {@code 0.0} when nothing matches
     */
    public double stockFor(UUID jobOrderId, UUID materialId, Integer qualityFloor) {
      return sumStockAtFloor(rowsByOrderAndMaterial, jobOrderId, materialId, qualityFloor);
    }
  }

  /**
   * Enriches the item order's aggregated-material rows with {@code currentStock} at each bucket's
   * quality floor and, for SK orders, with claims and open amount; non-SK rows keep empty claims
   * and a {@code null} open amount.
   *
   * @param jobOrder the item order
   * @param claimByBucket the SK claim view keyed by {@link #bucketKey}, or empty for non-SK orders
   * @param stockResolver resolves the order-linked stock of one material at a quality floor
   * @return the stock- and claim-enriched rows
   */
  private List<AggregatedMaterialDto> enrichAggregatedWithClaims(
      JobOrder jobOrder, Map<String, ClaimBucketDto> claimByBucket, StockResolver stockResolver) {
    return jobOrderItemService.aggregateMaterials(jobOrder).stream()
        .map(
            agg -> {
              Integer minQuality = qualityFloorFor(agg.qualityRequirement());
              double stock =
                  stockResolver.stockFor(jobOrder.getId(), agg.material().id(), minQuality);
              ClaimBucketDto bucket =
                  claimByBucket.get(
                      bucketKey(agg.material().id(), agg.qualityRequirement().name()));
              return new AggregatedMaterialDto(
                  agg.material(),
                  agg.qualityRequirement(),
                  agg.totalQuantity(),
                  stock,
                  bucket != null ? bucket.claims() : agg.claims(),
                  bucket != null ? bucket.openRemaining() : agg.openAmount());
            })
        .toList();
  }

  /**
   * Resolves the order-linked stock of one material at a quality floor for {@link
   * #mapToDtoWithStock(JobOrder, StockResolver, ClaimResolver)}. The single-order path backs it
   * with the per-order {@code SUM} query; the paged list backs it with an in-memory sum over the
   * page-batched stock index (REQ-DATA-003).
   */
  @FunctionalInterface
  private interface StockResolver {
    /**
     * Returns the stock of {@code materialId} linked to {@code jobOrderId} with quality at least
     * {@code qualityFloor}, with the semantics of {@code
     * sumAmountByMaterialAndJobOrderAndMinQuality}.
     *
     * @param jobOrderId the order the stock is linked to
     * @param materialId the material to sum
     * @param qualityFloor the minimum quality, or {@code null} for no floor
     * @return the summed amount, {@code 0.0} when nothing matches
     */
    double stockFor(UUID jobOrderId, UUID materialId, Integer qualityFloor);
  }

  /**
   * Resolves the SK claim view of one order for {@link #mapToDtoWithStock(JobOrder, StockResolver,
   * ClaimResolver)}. The single-order path backs it with the per-order claim query; the paged list
   * backs it with the page-batched claim lookup (REQ-DATA-003). Only invoked for SK-responsible
   * orders.
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
   * Loads every linked material inventory row of the given orders in one query, indexed by order id
   * and material id (REQ-DATA-003). Game-item earmarks are excluded by the query.
   *
   * @param orderIds the orders whose linked stock to index; empty yields an empty index
   * @return order id to material id to linked material rows, never {@code null}
   */
  private Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> loadStockIndex(
      Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    return inventoryItemRepository.findMaterialStockRowsByJobOrderIds(orderIds).stream()
        .collect(
            Collectors.groupingBy(
                JobOrderMaterialStockRow::jobOrderId,
                Collectors.groupingBy(JobOrderMaterialStockRow::materialId)));
  }

  /**
   * Sums the pre-loaded rows of one order and material at a quality floor, with the semantics of
   * {@code sumAmountByMaterialAndJobOrderAndMinQuality}.
   *
   * @param stockIndex the index from {@link #loadStockIndex(Collection)}
   * @param jobOrderId the order to sum within
   * @param materialId the material to sum
   * @param qualityFloor the minimum quality, or {@code null} for no floor
   * @return the summed amount; {@code 0.0} when nothing matches
   */
  private static double sumStockAtFloor(
      @NotNull Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> stockIndex,
      UUID jobOrderId,
      UUID materialId,
      Integer qualityFloor) {
    Map<UUID, List<JobOrderMaterialStockRow>> byMaterial = stockIndex.get(jobOrderId);
    if (byMaterial == null) {
      return 0.0;
    }
    List<JobOrderMaterialStockRow> rows = byMaterial.get(materialId);
    if (rows == null) {
      return 0.0;
    }
    double sum = 0.0;
    for (JobOrderMaterialStockRow row : rows) {
      if (row.amount() == null) {
        continue;
      }
      if (qualityFloor == null || (row.quality() != null && row.quality() >= qualityFloor)) {
        sum += row.amount();
      }
    }
    return sum;
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
   * Builds the composite key identifying a material bucket ({@code materialId|QUALITY}) used to
   * join claim buckets onto the material / aggregated rows.
   *
   * @param materialId the material id.
   * @param qualityName the {@code GOOD}/{@code NONE} quality name.
   * @return the composite bucket key.
   */
  @NotNull
  private static String bucketKey(UUID materialId, String qualityName) {
    return materialId + "|" + qualityName;
  }
}
