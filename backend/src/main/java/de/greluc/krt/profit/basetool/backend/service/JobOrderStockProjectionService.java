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
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Projects a managed {@link JobOrder} (or a whole page of them) into a {@link JobOrderDto} with its
 * per-bucket order-linked stock and — for SK-responsible orders — its per-squadron material claims.
 * Extracted from {@code JobOrderService} (L2, #921) so the read-projection concern lives on its
 * own, behind the exact same assembly logic.
 *
 * <p>Read-only: it only maps and sums, never mutating an entity, so it runs inside the caller's
 * transaction. The DTO assembly lives in exactly one place ({@link #mapToDtoWithStock(JobOrder,
 * StockResolver, ClaimResolver)}) shared by the single-order write paths (per-order queries) and
 * the paged list ({@link #mapPageWithStock(Page)}, page-batched lookups — REQ-DATA-003), so both
 * behave identically.
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
  public JobOrderDto mapToDtoWithStock(JobOrder jobOrder) {
    StockResolver stockResolver =
        (orderId, materialId, floor) -> {
          Double stock =
              inventoryItemRepository.sumAmountByMaterialAndJobOrderAndMinQuality(
                  materialId, orderId, floor);
          return stock != null ? stock : 0.0;
        };
    // Lambda (not a bound method reference) so the claim service is dereferenced lazily, only when
    // the resolver actually runs — i.e. for SK orders — mirroring the original conditional call.
    return mapToDtoWithStock(
        jobOrder, stockResolver, order -> materialClaimService.getClaimBucketsForOrder(order));
  }

  /**
   * Core order projection shared by the single-order write paths and the paged list. The {@code
   * stockResolver} and {@code claimResolver} abstract where the per-bucket stock and the SK claim
   * view come from — the single-order path backs them with per-order queries, the list path with
   * page-batched lookups (REQ-DATA-003) — so the DTO assembly itself lives in exactly one place and
   * behaves identically on both paths.
   *
   * @param jobOrder the managed order to project.
   * @param stockResolver resolves the order-linked stock of one material at a quality floor.
   * @param claimResolver resolves the SK claim view of one order ({@code List.of()} for non-SK).
   * @return the assembled order DTO with per-bucket stock and (for SK orders) claims.
   */
  private JobOrderDto mapToDtoWithStock(
      JobOrder jobOrder, StockResolver stockResolver, ClaimResolver claimResolver) {
    JobOrderDto baseDto = jobOrderMapper.toDto(jobOrder);

    // Phase 5 (#345): on a public SK order, every material/aggregated bucket carries the
    // per-squadron
    // claims + open-remaining; private (squadron) orders carry none (claims empty, openAmount
    // null),
    // so the detail UI renders no claim columns for them.
    Map<String, de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto> claimByBucket =
        isSpecialCommandResponsible(jobOrder)
            ? claimResolver.claimsFor(jobOrder).stream()
                .collect(
                    Collectors.toMap(
                        b -> bucketKey(b.material().id(), b.qualityRequirement().name()), b -> b))
            : Map.of();

    List<de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto> updatedMaterials =
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
                  // MATERIAL bucket quality mirrors aggregateMaterials(): a 650-floor is GOOD,
                  // "Keine" (null minQuality) is NONE.
                  String qualityName =
                      matDto.minQuality() != null
                          ? de.greluc.krt.profit.basetool.backend.model.QualityRequirement.GOOD
                              .name()
                          : de.greluc.krt.profit.basetool.backend.model.QualityRequirement.NONE
                              .name();
                  de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto bucket =
                      claimByBucket.get(bucketKey(matDto.material().id(), qualityName));
                  return new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialDto(
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
    List<de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemHandoverDto> itemHandovers =
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
        // The full projection is never redacted; the per-order redaction decision is stamped later
        // by the read path (JobOrderService.getJobOrderById) via JobOrderDto#withRedacted.
        false);
  }

  /**
   * Projects a whole page of orders, batching the per-row enrichment once for the page
   * (REQ-DATA-003): instead of one stock {@code SUM} per material per order plus one claim query
   * per SK order, load every order's linked stock in a single query and sum the buckets in memory,
   * and load every SK order's claims in a single query. The single-order write paths keep the
   * per-order resolvers via {@link #mapToDtoWithStock(JobOrder)}.
   *
   * @param page the scoped page of managed orders to project.
   * @return the page mapped to DTOs, stock- and claim-enriched.
   */
  public Page<JobOrderDto> mapPageWithStock(Page<JobOrder> page) {
    List<JobOrder> orders = page.getContent();
    Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> stockIndex =
        loadStockIndex(orders.stream().map(JobOrder::getId).toList());
    Map<UUID, List<de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto>> claimsByOrder =
        materialClaimService.getClaimBucketsForOrders(
            orders.stream()
                .filter(JobOrderStockProjectionService::isSpecialCommandResponsible)
                .toList());
    StockResolver stockResolver =
        (orderId, materialId, floor) -> sumStockAtFloor(stockIndex, orderId, materialId, floor);
    ClaimResolver claimResolver = order -> claimsByOrder.getOrDefault(order.getId(), List.of());
    return page.map(o -> mapToDtoWithStock(o, stockResolver, claimResolver));
  }

  /**
   * Rebuilds the item order's aggregated-material rows with their collection stock and (for SK
   * orders) their per-bucket claims + open-remaining. The base rows come from {@link
   * JobOrderItemService#aggregateMaterials} with neutral stock/claim fields; this enriches each
   * with {@code currentStock} — the order-linked inventory summed at the bucket's quality floor
   * ({@code GOOD} → {@link #GOOD_QUALITY_FLOOR}, {@code NONE} → no floor), the same per-bucket sum
   * the MATERIAL rows use — so the overview can show material-collection progress (#595). For a
   * non-SK order {@code claimByBucket} is empty, so every row keeps its empty claims / {@code null}
   * open-amount but still gains its stock.
   *
   * @param jobOrder the item order.
   * @param claimByBucket the SK claim view keyed by {@link #bucketKey}, or empty for non-SK orders.
   * @param stockResolver resolves the order-linked stock of one material at a quality floor (per-
   *     order query on the single-order path, page-batched lookup on the list path).
   * @return the aggregated rows, stock- and claim-enriched.
   */
  private List<AggregatedMaterialDto> enrichAggregatedWithClaims(
      JobOrder jobOrder,
      Map<String, de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto> claimByBucket,
      StockResolver stockResolver) {
    return jobOrderItemService.aggregateMaterials(jobOrder).stream()
        .map(
            agg -> {
              Integer minQuality =
                  agg.qualityRequirement()
                          == de.greluc.krt.profit.basetool.backend.model.QualityRequirement.GOOD
                      ? GOOD_QUALITY_FLOOR
                      : null;
              double stock =
                  stockResolver.stockFor(jobOrder.getId(), agg.material().id(), minQuality);
              de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto bucket =
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
     * Returns the total stock of {@code materialId} linked to {@code jobOrderId} whose quality
     * meets or exceeds {@code qualityFloor} ({@code null} floor = no quality restriction); {@code
     * 0.0} when nothing matches — the exact semantics of {@code
     * sumAmountByMaterialAndJobOrderAndMinQuality}.
     *
     * @param jobOrderId the order the stock is linked to.
     * @param materialId the material to sum.
     * @param qualityFloor the minimum quality, or {@code null} for no floor.
     * @return the summed amount, never negative, {@code 0.0} when empty.
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
    List<de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto> claimsFor(JobOrder order);
  }

  /**
   * Loads every job-order-linked <em>material</em> inventory row for the given orders in one query
   * and indexes it by order id then material id, so the paged list can sum each material bucket at
   * its own quality floor in memory (REQ-DATA-003) instead of firing a {@code SUM} aggregate per
   * bucket per order. Game-item earmarks (V220, REQ-INV-029 — created by the production
   * auto-earmark) are excluded in-query by {@code findMaterialStockRowsByJobOrderIds}; without that
   * guard they would surface here as {@code materialId = null} rows and NPE the {@code groupingBy}
   * below, 500ing the paged order list.
   *
   * @param orderIds the orders whose linked stock to index; empty yields an empty index.
   * @return order id → material id → the linked material inventory rows, never {@code null}.
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
   * Sums the pre-loaded stock rows of one (order, material) bucket at a quality floor, reproducing
   * the {@code COALESCE(SUM(amount), 0.0) WHERE (:floor IS NULL OR quality >= :floor)} semantics of
   * {@code sumAmountByMaterialAndJobOrderAndMinQuality} entirely in memory.
   *
   * @param stockIndex the page-batched index from {@link #loadStockIndex(Collection)}.
   * @param jobOrderId the order to sum within.
   * @param materialId the material to sum.
   * @param qualityFloor the minimum quality, or {@code null} for no floor.
   * @return the summed amount; {@code 0.0} when the bucket has no matching rows.
   */
  private static double sumStockAtFloor(
      Map<UUID, Map<UUID, List<JobOrderMaterialStockRow>>> stockIndex,
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
   * {@code true} iff the order is responsible to a Spezialkommando — the only orders that carry
   * material claims (Phase 5, #345).
   *
   * @param jobOrder the order.
   * @return whether the order is a public SK order.
   */
  private static boolean isSpecialCommandResponsible(JobOrder jobOrder) {
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
  private static String bucketKey(UUID materialId, String qualityName) {
    return materialId + "|" + qualityName;
  }
}
