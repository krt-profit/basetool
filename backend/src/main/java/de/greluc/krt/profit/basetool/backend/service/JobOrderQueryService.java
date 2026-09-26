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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.JobOrderMapper;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemNeedDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.service.JobOrderStockProjectionService.OrderLinkedStockIndex;
import de.greluc.krt.profit.basetool.backend.support.QuantityTypeRounding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query side of the job-order domain: the scoped list, the requester-side "Meine
 * Aufträge" list, the reference typeahead, the order detail and the link-inventory pickers.
 *
 * <p>Every read applies the caller's visibility scope ({@link OwnerScopeService}) in the query.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderQueryService {

  private final JobOrderRepository jobOrderRepository;
  private final MaterialRepository materialRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final OwnerScopeService ownerScopeService;
  private final JobOrderMapper jobOrderMapper;
  private final SquadronMapper squadronMapper;
  private final JobOrderItemService jobOrderItemService;
  private final JobOrderStockProjectionService jobOrderStockProjectionService;
  private final JobOrderMaterialRequirementResolver materialRequirementResolver;
  private final InventoryItemMapper inventoryItemMapper;

  /**
   * Paged list with an optional status filter, always constrained to the caller's visibility scope.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param pageable page request
   * @return paged job orders as DTOs
   */
  public Page<JobOrderDto> getAllJobOrders(List<JobOrderStatus> statuses, Pageable pageable) {
    return getAllJobOrders(statuses, null, pageable);
  }

  /**
   * Paged list with optional status and squadron display filters, always constrained to the
   * caller's visibility scope.
   *
   * <p>SK-responsible orders are public, squadron-responsible orders private to that squadron and
   * admins; a caller outside the order workflow ({@link OwnerScopeService#canViewJobOrders()}) gets
   * an empty page. {@code squadronIds} can only narrow the result.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param squadronIds optional display filter matching the responsible or requesting side;
   *     null/empty means no restriction
   * @param pageable page request
   * @return paged job orders as DTOs, scoped to the caller's visibility
   */
  public Page<JobOrderDto> getAllJobOrders(
      List<JobOrderStatus> statuses, Collection<UUID> squadronIds, Pageable pageable) {
    if (!ownerScopeService.canViewJobOrders()) {
      return Page.empty(pageable);
    }
    List<JobOrderStatus> effectiveStatuses =
        (statuses == null || statuses.isEmpty()) ? List.of(JobOrderStatus.values()) : statuses;
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    boolean noSquadronFilter = squadronIds == null || squadronIds.isEmpty();
    Collection<UUID> effectiveSquadronIds =
        noSquadronFilter ? Set.of(new UUID(0L, 0L)) : squadronIds;
    Page<JobOrder> page =
        jobOrderRepository.findScopedJobOrders(
            effectiveStatuses,
            noSquadronFilter,
            effectiveSquadronIds,
            scope.adminAllScope(),
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            pageable);

    return jobOrderStockProjectionService.mapPageWithStock(page);
  }

  /**
   * Paged list of the orders requested by org units the caller is a direct member of, the "Meine
   * Auftr&auml;ge" list (REQ-ORDERS-023). A memberless caller gets an empty page.
   *
   * @param statuses optional status filter; null/empty means "all"
   * @param pageable page request
   * @return paged job orders requested by the caller's org units
   */
  public Page<JobOrderDto> getRequestedJobOrders(List<JobOrderStatus> statuses, Pageable pageable) {
    Set<UUID> requesterOrgUnitIds = ownerScopeService.currentDirectMembershipOrgUnitIds();
    if (requesterOrgUnitIds.isEmpty()) {
      return Page.empty(pageable);
    }
    List<JobOrderStatus> effectiveStatuses =
        (statuses == null || statuses.isEmpty()) ? List.of(JobOrderStatus.values()) : statuses;
    Page<JobOrder> page =
        jobOrderRepository.findRequestedOrders(effectiveStatuses, requesterOrgUnitIds, pageable);
    return jobOrderStockProjectionService.mapPageWithStock(page);
  }

  /**
   * Lightweight reference projection of the active orders the caller may see, for typeaheads and
   * pickers.
   *
   * @param withNeeds whether to include each order's outstanding per-material need (REQ-INV-039)
   * @return active job orders the caller may see, as reference DTOs
   */
  public List<JobOrderReferenceDto> findAllActiveReference(boolean withNeeds) {
    if (!ownerScopeService.canViewJobOrders()) {
      return List.of();
    }
    List<JobOrder> visible =
        jobOrderRepository.findAllActiveWithMaterials().stream()
            .filter(ownerScopeService::canSeeJobOrder)
            .toList();
    List<UUID> needIds = withNeeds ? visible.stream().map(JobOrder::getId).toList() : List.of();
    OrderLinkedStockIndex stockIndex =
        jobOrderStockProjectionService.loadOrderLinkedStockIndex(needIds);
    Map<UUID, Map<UUID, Double>> itemStockByOrder =
        needIds.isEmpty() ? Map.of() : loadItemStockIndex(needIds);
    return visible.stream()
        .map(
            o ->
                new JobOrderReferenceDto(
                    o.getId(),
                    o.getDisplayId(),
                    o.getHandle(),
                    o.getStatus(),
                    squadronMapper.orgUnitToReferenceDto(o.getRequestingOrgUnit()),
                    o.getMaterials() != null
                        ? o.getMaterials().stream().map(jobOrderMapper::toDto).toList()
                        : List.of(),
                    List.copyOf(jobOrderItemService.requiredMaterialIds(o)),
                    List.copyOf(jobOrderItemService.requiredGameItemIds(o)),
                    withNeeds ? materialNeedsOf(o, stockIndex) : List.of(),
                    withNeeds ? gameItemNeedsOf(o, itemStockByOrder) : List.of()))
        .toList();
  }

  /**
   * Folds one order's outstanding per-bucket need for the allocation pickers (REQ-INV-039): the
   * requirement minus the order-linked stock at the bucket's quality. Material claims are ignored.
   *
   * @param order the managed order to fold.
   * @param stockIndex the batched order-linked stock lookup.
   * @return the order's buckets, empty when it requires no material.
   */
  private List<JobOrderMaterialNeedDto> materialNeedsOf(
      @NotNull JobOrder order, @NotNull OrderLinkedStockIndex stockIndex) {
    return materialRequirementResolver.requirementsOf(order).stream()
        .map(
            requirement -> {
              Integer qualityFloor =
                  JobOrderStockProjectionService.qualityFloorFor(requirement.quality());
              double required =
                  QuantityTypeRounding.roundForQuantityType(
                      requirement.requiredAmount(), requirement.material());
              double booked =
                  QuantityTypeRounding.roundForQuantityType(
                      stockIndex.stockFor(order.getId(), requirement.material().id(), qualityFloor),
                      requirement.material());
              return new JobOrderMaterialNeedDto(
                  requirement.material().id(),
                  qualityFloor,
                  required,
                  booked,
                  Math.max(
                      0.0,
                      QuantityTypeRounding.roundForQuantityType(
                          required - booked, requirement.material())));
            })
        .toList();
  }

  /**
   * Loads every order's earmarked item stock in one query and folds it to order &rarr; game item
   * &rarr; summed slice (REQ-DATA-003).
   *
   * @param jobOrderIds the orders to index; never empty when this is called.
   * @return the summed earmarks, never {@code null}.
   */
  @NotNull
  private Map<UUID, Map<UUID, Double>> loadItemStockIndex(List<UUID> jobOrderIds) {
    Map<UUID, Map<UUID, Double>> index = new HashMap<>();
    for (JobOrderGameItemStockRow row :
        inventoryItemRepository.findGameItemStockRowsByJobOrderIds(jobOrderIds)) {
      if (row.jobOrderId() == null || row.gameItemId() == null) {
        continue;
      }
      index
          .computeIfAbsent(row.jobOrderId(), unused -> new HashMap<>())
          .merge(row.gameItemId(), row.amount() == null ? 0.0 : row.amount(), Double::sum);
    }
    return index;
  }

  /**
   * Folds one ITEM order's outstanding need per game item for the item-mode allocation pickers
   * (REQ-INV-039), summed over all lines of the same game item as {@code ordered - delivered -
   * earmarked}.
   *
   * @param order the managed order to fold; a MATERIAL order yields an empty list.
   * @param itemStockByOrder the batched earmark sums.
   * @return the order's per-game-item needs, empty when it orders no items.
   */
  @NotNull
  private List<JobOrderGameItemNeedDto> gameItemNeedsOf(
      @NotNull JobOrder order, @NotNull Map<UUID, Map<UUID, Double>> itemStockByOrder) {
    if (order.getItems() == null || order.getItems().isEmpty()) {
      return List.of();
    }
    Map<UUID, int[]> lineTotals = new LinkedHashMap<>();
    for (JobOrderItem line : order.getItems()) {
      if (line.getGameItem() == null) {
        continue;
      }
      int[] totals = lineTotals.computeIfAbsent(line.getGameItem().getId(), key -> new int[2]);
      totals[0] += line.getAmount() != null ? line.getAmount() : 0;
      totals[1] += line.getDeliveredAmount() != null ? line.getDeliveredAmount() : 0;
    }
    Map<UUID, Double> earmarked = itemStockByOrder.getOrDefault(order.getId(), Map.of());
    List<JobOrderGameItemNeedDto> needs = new ArrayList<>();
    lineTotals.forEach(
        (gameItemId, totals) -> {
          int ordered = totals[0];
          int delivered = totals[1];
          int allocated = (int) Math.round(earmarked.getOrDefault(gameItemId, 0.0));
          needs.add(
              new JobOrderGameItemNeedDto(
                  gameItemId,
                  ordered,
                  delivered,
                  allocated,
                  Math.max(0, ordered - delivered - allocated)));
        });
    return needs;
  }

  /**
   * Returns the order as a DTO.
   *
   * @param id job order primary key
   * @return the order as a DTO
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public JobOrderDto getJobOrderById(UUID id) {
    JobOrder jobOrder =
        Entities.require(jobOrderRepository.findById(id), () -> "JobOrder not found: " + id);
    JobOrderDto dto = jobOrderStockProjectionService.mapToDtoWithStock(jobOrder);
    return ownerScopeService.canSeeJobOrder(jobOrder) ? dto : dto.withRedacted(true);
  }

  /**
   * Returns the inventory items that may be linked to a job order's material: matching material and
   * minimum quality, and not linked to another order.
   *
   * @param jobOrderId target job order
   * @param materialId target material on that order
   * @return list of inventory items as DTOs
   */
  public List<InventoryItemDto> getInventoryItemsForJobOrderMaterial(
      UUID jobOrderId, UUID materialId) {
    Entities.require(
        jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    Entities.require(
        materialRepository.findById(materialId), () -> "Material not found: " + materialId);

    return inventoryItemRepository.findByJobOrderIdAndMaterialId(jobOrderId, materialId).stream()
        .map(inventoryItemMapper::toDto)
        .sorted(
            Comparator.comparing(
                    (InventoryItemDto item) ->
                        item.user() != null && item.user().effectiveName() != null
                            ? item.user().effectiveName()
                            : "",
                    Comparator.naturalOrder())
                .thenComparing(
                    item -> item.quality() != null ? item.quality() : 0, Comparator.reverseOrder())
                .thenComparing(
                    item ->
                        item.location() != null && item.location().name() != null
                            ? item.location().name()
                            : "",
                    Comparator.naturalOrder())
                .thenComparing(
                    item -> item.amount() != null ? item.amount() : 0.0, Comparator.reverseOrder()))
        .toList();
  }

  /**
   * Returns the inventory linked to the order whose material or game item the order does not
   * require (REQ-ORDERS-019), so a mis-assignment can be spotted and undone.
   *
   * @param jobOrderId the order to inspect.
   * @return the orphaned linked items, material rows followed by game-item rows; empty when every
   *     link matches a requirement.
   * @throws NotFoundException when the order does not exist.
   */
  public List<InventoryItemDto> getOrphanedLinkedInventory(UUID jobOrderId) {
    JobOrder jobOrder =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    Set<UUID> requiredMaterials = jobOrderItemService.requiredMaterialIds(jobOrder);
    Set<UUID> requiredGameItems = jobOrderItemService.requiredGameItemIds(jobOrder);
    return Stream.concat(
            inventoryItemRepository.findByJobOrderIdOrdered(jobOrderId).stream()
                .filter(
                    item ->
                        item.getMaterial() == null
                            || !requiredMaterials.contains(item.getMaterial().getId())),
            inventoryItemRepository.findGameItemRowsByJobOrderIdOrdered(jobOrderId).stream()
                .filter(
                    item ->
                        item.getGameItem() == null
                            || !requiredGameItems.contains(item.getGameItem().getId())))
        .map(inventoryItemMapper::toDto)
        .toList();
  }
}
