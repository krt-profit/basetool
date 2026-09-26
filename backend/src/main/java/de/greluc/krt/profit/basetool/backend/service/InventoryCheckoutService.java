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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BulkRebookMode;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the inventory: book-out, rebooking, stock merge, bulk checkout, delivered toggle
 * and the admin global wipe.
 *
 * <p>Inventory is append-only: a transfer or rebooking inserts a new row and decrements or deletes
 * the source, and only {@link #mergeStockIfRequested} folds rows together. Partial moves {@code
 * saveAndFlush} the reduced source so its {@code @Version} stays current (REQ-FE-003).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryCheckoutService {

  /**
   * Tolerance for comparing {@code double} inventory amounts; smaller differences are
   * floating-point noise, since quantities carry at most three decimals.
   */
  private static final double QUANTITY_EPSILON = 1e-4;

  /**
   * Max length of the {@code inventory_item.note} column (V61 {@code VARCHAR(1000)}). A stock merge
   * concatenates the distinct notes of the folded rows and truncates the result to this length so
   * the write can never overflow the column.
   */
  private static final int NOTE_MAX_LENGTH = 1000;

  /** Separator used when a stock merge concatenates the distinct notes of the folded rows. */
  private static final String NOTE_MERGE_SEPARATOR = "\n";

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final MissionFinanceEntryRepository missionFinanceEntryRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final MaterialExchangeOfferRepository materialExchangeOfferRepository;
  private final InventoryItemMapper inventoryItemMapper;
  private final OwnerScopeService ownerScopeService;
  private final AuditService auditService;

  /**
   * Discards, transfers or sells part of an inventory item, deleting the row when the remainder
   * falls below {@link #QUANTITY_EPSILON}.
   *
   * <p>A {@code null} type is inferred: {@code TRANSFER} when a target user or location is given,
   * otherwise {@code DISCARD}. A transfer inserts a new row at the target; a sale books mission
   * income.
   *
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the amount
   *     exceeds the stock, is fractional on a whole-unit row without depleting it, a sale lacks its
   *     terminal or amount, or a {@code TRANSFER} has no target
   */
  @Nullable
  @Transactional
  public InventoryItemDto bookOutInventoryItem(
      UUID id, InventoryItemBookOutDto dto, UUID currentUserId, boolean isAdmin) {
    InventoryItem item =
        Entities.require(inventoryItemRepository.findById(id), "Inventory item not found");

    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);

    if (!item.getUser().getId().equals(currentUserId) && !isAdmin) {
      throw new AccessDeniedException("You are not allowed to book out this inventory item");
    }

    if (dto.amount() > item.getAmount()) {
      throw new BadRequestException("Cannot book out more than the available amount");
    }

    if (requiresWholeUnits(item)
        && dto.amount() % 1 != 0
        && Double.compare(dto.amount(), item.getAmount()) != 0) {
      throw new BadRequestException(wholeUnitAmountDetail(item));
    }

    if (item.getGameItem() != null
        && dto.missionReductions() != null
        && !dto.missionReductions().isEmpty()) {
      throw new BadRequestException("Game-item stock carries no mission earmarks");
    }

    CheckoutType checkoutType = dto.type();
    if (checkoutType == null) {
      checkoutType =
          (dto.targetUserId() != null || dto.targetLocationId() != null)
              ? CheckoutType.TRANSFER
              : CheckoutType.DISCARD;
    }

    if (checkoutType == CheckoutType.SELL) {
      if (dto.terminal() == null || dto.terminal().isBlank()) {
        throw new BadRequestException("Terminal is required for selling");
      }
      if (dto.sellAmount() == null || dto.sellAmount().compareTo(BigDecimal.ZERO) < 0) {
        throw new BadRequestException("Sell amount is required and must be positive");
      }
    }

    if (checkoutType == CheckoutType.TRANSFER
        && dto.targetUserId() == null
        && dto.targetLocationId() == null) {
      throw new BadRequestException("Transfer requires a target user or location");
    }

    double remainingAmount = InventoryItem.roundToScuScale(item.getAmount() - dto.amount());

    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = catalogName(item);
    final UUID sourceOwnerId = item.getUser().getId();
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;
    List<UUID> financeEntryIds = List.of();

    if (checkoutType == CheckoutType.TRANSFER) {
      return bookOutTransfer(
          item, dto, remainingAmount, sourceId, sourceLabel, materialName, depleted);
    }

    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.jobOrderReductions(), dto.amount(), true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.missionReductions(), dto.amount(), false);

    if (checkoutType == CheckoutType.SELL) {
      financeEntryIds = createSaleFinanceEntries(item, dto, currentUserId, missionReductions);
    }
    AllocationReductions.applyPlan(item, orderReductions, true);
    AllocationReductions.applyPlan(item, missionReductions, false);

    if (remainingAmount <= QUANTITY_EPSILON) {
      inventoryItemRepository.delete(item);
      recordBookOutTail(
          checkoutType,
          sourceId,
          sourceLabel,
          materialName,
          sourceOwnerId,
          dto,
          0.0,
          financeEntryIds);
      return null;
    } else {
      item.setAmount(remainingAmount);
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
      ratchetBoardOffersToStock(sourceId, remainingAmount);
      recordBookOutTail(
          checkoutType,
          sourceId,
          sourceLabel,
          materialName,
          sourceOwnerId,
          dto,
          remainingAmount,
          financeEntryIds);
      return inventoryItemMapper.toDto(saved);
    }
  }

  /**
   * Books out a {@code TRANSFER}: inserts a new row for {@code dto.amount()} at the target,
   * decrements or deletes the source, and records the audit event.
   *
   * @param item the managed source row
   * @param dto the book-out request (target user/location/org-unit and amount)
   * @param remainingAmount the source's post-decrement amount (already rounded)
   * @param sourceId the source row id snapshot
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param depleted whether the source row depletes to zero
   * @return the DTO of the newly created target row
   * @throws NotFoundException when the target user or location is unknown
   * @throws BadRequestException when the transfer changes neither user nor location
   */
  private InventoryItemDto bookOutTransfer(
      @NotNull InventoryItem item,
      InventoryItemBookOutDto dto,
      double remainingAmount,
      UUID sourceId,
      String sourceLabel,
      String materialName,
      boolean depleted) {
    User targetUser = item.getUser();
    if (dto.targetUserId() != null && !dto.targetUserId().equals(item.getUser().getId())) {
      targetUser =
          Entities.require(userRepository.findById(dto.targetUserId()), "Target user not found");
    }

    Location targetLocation = item.getLocation();
    if (dto.targetLocationId() != null
        && !dto.targetLocationId().equals(item.getLocation().getId())) {
      targetLocation =
          Entities.require(
              locationRepository.findById(dto.targetLocationId()), "Target location not found");
    }

    if (targetUser.getId().equals(item.getUser().getId())
        && targetLocation.getId().equals(item.getLocation().getId())) {
      throw new BadRequestException("Transfer must change either the user or the location");
    }

    final OrgUnit targetOwningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(
            targetUser, dto.targetOwningOrgUnitId());

    InventoryItem newItem = new InventoryItem();
    newItem.setUser(targetUser);
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(item.getMaterial());
    newItem.setGameItem(item.getGameItem());
    newItem.setLocation(targetLocation);
    newItem.setQuality(item.getQuality());
    newItem.setAmount(InventoryItem.roundToScuScale(dto.amount()));
    newItem.setPersonal(item.getPersonal());
    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.jobOrderReductions(), dto.amount(), true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.missionReductions(), dto.amount(), false);
    applyTransferInherit(item, newItem, orderReductions, missionReductions);
    final InventoryItem savedNew = inventoryItemRepository.save(newItem);
    AllocationReductions.applyPlan(item, orderReductions, true);
    AllocationReductions.applyPlan(item, missionReductions, false);
    if (remainingAmount <= QUANTITY_EPSILON) {
      inventoryItemRepository.delete(item);
    } else {
      item.setAmount(remainingAmount);
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      inventoryItemRepository.saveAndFlush(item);
      ratchetBoardOffersToStock(sourceId, remainingAmount);
    }
    auditService.record(
        AuditEventType.INVENTORY_ITEM_TRANSFERRED,
        sourceId,
        sourceLabel,
        targetUser.getId(),
        AuditDetails.of("material", materialName)
            .with("amount", dto.amount())
            .with("toLoc", targetLocation != null ? targetLocation.getName() : "—")
            .with("newRow", newItem.getId())
            .with("depleted", depleted));
    final InventoryItem mergedTarget =
        mergeStockIfRequested(savedNew, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(mergedTarget);
  }

  /**
   * Books the mission income of a {@code SELL} book-out (REQ-INV-027): each mission the sold SCU
   * was deducted from gets {@code sellAmount × deductedScu / totalSoldScu} as a squadron {@code
   * INCOME} {@link MissionFinanceEntry}. Shares of missions the seller does not participate in, and
   * SCU not taken from a mission, stay the seller's personal proceeds. Must run before the
   * reductions are applied.
   *
   * @param item the managed source row with its mission allocations
   * @param dto the book-out request (sell amount, terminal, total sold amount)
   * @param currentUserId the selling participant's user id
   * @param missionReductions the mission plan (missionId to deducted SCU)
   * @return the created finance-entry ids; empty for a fully personal sale
   */
  @NotNull
  private List<UUID> createSaleFinanceEntries(
      InventoryItem item,
      @NotNull InventoryItemBookOutDto dto,
      UUID currentUserId,
      Map<UUID, Double> missionReductions) {
    BigDecimal totalSold = BigDecimal.valueOf(dto.amount() != null ? dto.amount() : 0.0);
    if (missionReductions.isEmpty() || totalSold.signum() <= 0) {
      return List.of();
    }
    BigDecimal proceeds = dto.sellAmount() != null ? dto.sellAmount() : BigDecimal.ZERO;

    List<MissionFinanceEntry> entries = new ArrayList<>();
    for (Map.Entry<UUID, Double> reduction : missionReductions.entrySet()) {
      InventoryMissionAllocation slice =
          InventoryAllocations.missionSlice(item, reduction.getKey());
      if (slice == null || slice.getMission() == null) {
        continue;
      }
      Mission mission = slice.getMission();
      MissionParticipant participant =
          missionParticipantRepository
              .findByMissionIdAndUserId(mission.getId(), currentUserId)
              .orElse(null);
      if (participant == null) {
        continue;
      }
      BigDecimal credit =
          proceeds
              .multiply(BigDecimal.valueOf(reduction.getValue()))
              .divide(totalSold, 4, RoundingMode.HALF_UP);
      if (credit.signum() <= 0) {
        continue;
      }
      MissionFinanceEntry entry = new MissionFinanceEntry();
      entry.setMission(mission);
      entry.setParticipant(participant);
      entry.setType(FinanceType.INCOME);
      entry.setAmount(credit);
      entry.setNote("Sale of " + dto.amount() + "x " + catalogName(item) + " at " + dto.terminal());
      entries.add(entry);
    }

    List<UUID> financeEntryIds = new ArrayList<>();
    for (MissionFinanceEntry entry : entries) {
      missionFinanceEntryRepository.save(entry);
      financeEntryIds.add(entry.getId());
    }
    return financeEntryIds;
  }

  /**
   * Copies the reduced earmarks of a transfer onto the moved row as same-size earmarks, inheriting
   * each job-order slice's delivered flag (REQ-INV-027). Must run before the reductions are
   * applied.
   *
   * @param source the source row whose slices are read; never {@code null}
   * @param target the freshly built moved row to earmark; never {@code null}
   * @param orderReductions the job-order plan (orderId to SCU)
   * @param missionReductions the mission plan (missionId to SCU)
   */
  private void applyTransferInherit(
      InventoryItem source,
      InventoryItem target,
      Map<UUID, Double> orderReductions,
      Map<UUID, Double> missionReductions) {
    for (Map.Entry<UUID, Double> reduction : orderReductions.entrySet()) {
      InventoryJobOrderAllocation slice =
          InventoryAllocations.jobOrderSlice(source, reduction.getKey());
      if (slice != null && slice.getJobOrder() != null) {
        JobOrder order = slice.getJobOrder();
        InventoryAllocations.addJobOrder(
            target, order, reduction.getValue(), Boolean.TRUE.equals(slice.getDelivered()));
      }
    }
    for (Map.Entry<UUID, Double> reduction : missionReductions.entrySet()) {
      InventoryMissionAllocation slice =
          InventoryAllocations.missionSlice(source, reduction.getKey());
      if (slice != null && slice.getMission() != null) {
        InventoryAllocations.addMission(target, slice.getMission(), reduction.getValue());
      }
    }
  }

  /**
   * Records the audit event of a {@code DISCARD} or {@code SELL} book-out; a sale carries terminal,
   * amount and finance entries, a discard the consumed and remaining amounts.
   *
   * @param type the resolved checkout type (never {@code TRANSFER} here)
   * @param sourceId the source row id (snapshotted before a possible delete)
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param ownerId the source row owner's id
   * @param dto the book-out request (read for terminal/sell amount)
   * @param remaining the post-decrement amount (0 when the row was depleted)
   * @param financeEntryIds the finance entry ids created for a sale (empty otherwise)
   */
  private void recordBookOutTail(
      CheckoutType type,
      UUID sourceId,
      String sourceLabel,
      String materialName,
      UUID ownerId,
      InventoryItemBookOutDto dto,
      double remaining,
      List<UUID> financeEntryIds) {
    boolean rowDepleted = remaining <= QUANTITY_EPSILON;
    if (type == CheckoutType.SELL) {
      auditService.record(
          AuditEventType.INVENTORY_ITEM_SOLD,
          sourceId,
          sourceLabel,
          ownerId,
          AuditDetails.of("material", materialName)
              .with("amount", dto.amount())
              .with("terminalLength", dto.terminal() == null ? 0 : dto.terminal().trim().length())
              .with("sellAmount", dto.sellAmount())
              .with(
                  "financeEntries",
                  financeEntryIds.stream()
                      .filter(Objects::nonNull)
                      .map(UUID::toString)
                      .reduce((a, b) -> a + "," + b)
                      .orElse("-"))
              .with("depleted", rowDepleted));
    } else {
      auditService.record(
          AuditEventType.INVENTORY_ITEM_CONSUMED,
          sourceId,
          sourceLabel,
          ownerId,
          AuditDetails.of("type", type)
              .with("material", materialName)
              .with("amount", dto.amount())
              .with("remaining", remaining)
              .with("depleted", rowDepleted));
    }
  }

  /**
   * Moves part or all of a row between the owner's personal pool and the shared squadron pool
   * (Umbuchung, REQ-INV-007). The direction follows the source row's current {@code personal} flag;
   * the moved amount becomes a new row and the source is decremented or deleted.
   *
   * <p>Personalizing a row earmarked for a job order or mission is refused.
   *
   * @param id the source inventory row id
   * @param dto the rebooking payload (amount, version, target org-unit pool)
   * @param currentUserId the authenticated caller's user id
   * @param isAdmin whether the caller is an admin (bypasses the owner check)
   * @return the DTO of the new row
   * @throws NotFoundException when the source row or the picked org unit is unknown
   * @throws BadRequestException when the amount is non-positive, exceeds the stock, is fractional
   *     on a whole-unit row without moving the whole remainder, or a personalize would break the
   *     no-earmark rule
   */
  @Transactional
  public InventoryItemDto rebookPersonal(
      UUID id, InventoryItemPersonalRebookDto dto, UUID currentUserId, boolean isAdmin) {
    InventoryItem item =
        Entities.require(inventoryItemRepository.findById(id), "Inventory item not found");

    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);

    if (!item.getUser().getId().equals(currentUserId) && !isAdmin) {
      throw new AccessDeniedException("You are not allowed to rebook this inventory item");
    }

    if (dto.amount() == null || dto.amount() <= 0) {
      throw new BadRequestException("Rebooked amount must be positive");
    }
    if (dto.amount() > item.getAmount()) {
      throw new BadRequestException("Cannot rebook more than the available amount");
    }
    if (requiresWholeUnits(item)
        && dto.amount() % 1 != 0
        && Double.compare(dto.amount(), item.getAmount()) != 0) {
      throw new BadRequestException(wholeUnitAmountDetail(item));
    }

    final boolean sourcePersonal = Boolean.TRUE.equals(item.getPersonal());
    final boolean targetPersonal = !sourcePersonal;

    if (targetPersonal
        && (!item.getJobOrderAllocations().isEmpty() || !item.getMissionAllocations().isEmpty())) {
      throw new BadRequestException(
          "Stock assigned to a job order or mission cannot be marked personal");
    }

    final OrgUnit targetOwningOrgUnit =
        targetPersonal
            ? item.getOwningOrgUnit()
            : ownerScopeService.resolveOrgUnitForPickerOutputNullable(
                item.getUser(), dto.targetOwningOrgUnitId());

    final double remainingAmount = InventoryItem.roundToScuScale(item.getAmount() - dto.amount());
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;

    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = catalogName(item);
    final UUID ownerId = item.getUser().getId();

    InventoryItem newItem = new InventoryItem();
    newItem.setUser(item.getUser());
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(item.getMaterial());
    newItem.setGameItem(item.getGameItem());
    newItem.setLocation(item.getLocation());
    newItem.setQuality(item.getQuality());
    newItem.setAmount(InventoryItem.roundToScuScale(dto.amount()));
    newItem.setPersonal(targetPersonal);
    InventoryItem savedNew = inventoryItemRepository.save(newItem);

    if (depleted) {
      inventoryItemRepository.delete(item);
    } else {
      item.setAmount(remainingAmount);
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      inventoryItemRepository.saveAndFlush(item);
      ratchetBoardOffersToStock(sourceId, remainingAmount);
    }

    auditService.record(
        targetPersonal
            ? AuditEventType.INVENTORY_ITEM_PERSONALIZED
            : AuditEventType.INVENTORY_ITEM_DEPERSONALIZED,
        sourceId,
        sourceLabel,
        ownerId,
        AuditDetails.of("material", materialName)
            .with("amount", dto.amount())
            .with("newRow", newItem.getId())
            .with("targetOrgUnit", targetOwningOrgUnit != null ? targetOwningOrgUnit.getId() : "-")
            .with("depleted", depleted));

    final InventoryItem mergedTarget =
        mergeStockIfRequested(savedNew, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(mergedTarget);
  }

  /**
   * Folds every other row with the same physical stock identity into {@code row} (REQ-INV-026):
   * amounts summed, distinct notes concatenated, earmarks unioned, siblings deleted.
   *
   * <p>Always applies to {@code PIECE} materials and game items; for {@code SCU} only when {@code
   * clientRequestedMerge} is set. The group is locked {@code FOR UPDATE}, and rows backing a
   * Materialbörse offer are never changed or folded. Must run inside the caller's transaction.
   *
   * @param row the just-written target row (managed); the merge survivor
   * @param clientRequestedMerge the per-action opt-in for an {@code SCU} material (ignored for
   *     {@code PIECE} materials and game-item rows)
   * @return {@code row}, merged or unchanged
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InventoryItem mergeStockIfRequested(
      @NotNull InventoryItem row, boolean clientRequestedMerge) {
    final Material material = row.getMaterial();
    final boolean gameItemRow = row.getGameItem() != null;
    if (material == null && !gameItemRow) {
      return row;
    }
    final boolean autoMerge = gameItemRow || material.getQuantityType() == QuantityType.PIECE;
    if (!autoMerge && !clientRequestedMerge) {
      return row;
    }
    if (materialExchangeOfferRepository.existsByInventoryItemId(row.getId())) {
      return row;
    }

    final List<InventoryItem> group =
        gameItemRow
            ? inventoryItemRepository.findMergeGroupForUpdate(
                row.getUser().getId(),
                null,
                row.getGameItem().getId(),
                row.getLocation().getId(),
                null,
                row.getPersonal(),
                row.getOwningOrgUnit() != null ? row.getOwningOrgUnit().getId() : null)
            : inventoryItemRepository.findMergeGroupForUpdate(
                row.getUser().getId(),
                material.getId(),
                row.getLocation().getId(),
                row.getQuality(),
                row.getPersonal(),
                row.getOwningOrgUnit() != null ? row.getOwningOrgUnit().getId() : null);

    final List<InventoryItem> victims =
        group.stream().filter(candidate -> !candidate.getId().equals(row.getId())).toList();
    if (victims.isEmpty()) {
      return row;
    }

    double total = row.getAmount() != null ? row.getAmount() : 0.0;
    final Set<String> notes = new LinkedHashSet<>();
    collectNote(notes, row.getNote());
    for (InventoryItem victim : victims) {
      total += victim.getAmount() != null ? victim.getAmount() : 0.0;
      collectNote(notes, victim.getNote());
    }

    row.setAmount(InventoryItem.roundToScuScale(total));
    row.setNote(mergeNotes(notes));
    for (InventoryItem victim : victims) {
      InventoryAllocations.unionInto(row, victim);
    }

    inventoryItemRepository.deleteAll(victims);
    inventoryItemRepository.saveAndFlush(row);

    auditService.record(
        AuditEventType.INVENTORY_ITEM_MERGED,
        row.getId(),
        InventoryAuditLabels.label(row),
        row.getUser().getId(),
        AuditDetails.of("merged", victims.size())
            .with("total", row.getAmount())
            .with("q", row.getQuality())
            .with("trigger", autoMerge ? "auto" : "manual"));
    return row;
  }

  /**
   * Whether a row's amounts must be whole numbers: game-item rows and {@code PIECE} material rows.
   *
   * @param item the inventory row
   * @return {@code true} iff amounts on this row must be whole numbers
   */
  private static boolean requiresWholeUnits(@NotNull InventoryItem item) {
    return item.getGameItem() != null
        || (item.getMaterial() != null
            && item.getMaterial().getQuantityType() == QuantityType.PIECE);
  }

  /**
   * Builds the 400 detail for a fractional amount on a whole-unit row, naming the row's catalog
   * kind.
   *
   * @param item the whole-unit row the amount was rejected for
   * @return the catalog-appropriate problem detail
   */
  @NotNull
  private static String wholeUnitAmountDetail(@NotNull InventoryItem item) {
    return item.getGameItem() != null
        ? "Amount must be a whole number for item stock"
        : "Amount must be a whole number for PIECE materials";
  }

  /**
   * Returns a row's catalog display name for audit and finance snapshots: the material or game-item
   * name, or an em dash when neither is set.
   *
   * @param item the inventory row, read within the transaction
   * @return the row's catalog display name
   */
  private static String catalogName(InventoryItem item) {
    if (item.getMaterial() != null) {
      return item.getMaterial().getName();
    }
    return item.getGameItem() != null ? item.getGameItem().getName() : "—";
  }

  /**
   * Adds a note to the merge accumulator, trimmed to {@code null} and skipped when blank, so the
   * merged note carries only the distinct non-empty contributions in first-seen order.
   *
   * @param notes the ordered accumulator of distinct notes.
   * @param note the raw note of one folded row, possibly {@code null} or blank.
   */
  private static void collectNote(Set<String> notes, String note) {
    final String normalized = StringNormalization.trimToNull(note);
    if (normalized != null) {
      notes.add(normalized);
    }
  }

  /**
   * Joins the distinct merged notes with {@link #NOTE_MERGE_SEPARATOR}, truncating to {@link
   * #NOTE_MAX_LENGTH} so the write never overflows the {@code note} column.
   *
   * @param notes the ordered accumulator of distinct notes.
   * @return the combined note, or {@code null} when no folded row carried a note.
   */
  @Nullable
  private static String mergeNotes(Set<String> notes) {
    if (notes.isEmpty()) {
      return null;
    }
    final String joined = String.join(NOTE_MERGE_SEPARATOR, notes);
    return joined.length() > NOTE_MAX_LENGTH ? joined.substring(0, NOTE_MAX_LENGTH) : joined;
  }

  /**
   * Clamps any active Materialbörse offer on a Lager row down to the row's reduced stock
   * (REQ-MARKET-013): SCU for a material offer, whole units for an item offer. A no-op for rows
   * backing no offer; does not detach the persistence context.
   *
   * @param itemId the backing Lager row whose active offer to clamp
   * @param stock the row's new (reduced) stock
   */
  private void ratchetBoardOffersToStock(UUID itemId, double stock) {
    materialExchangeOfferRepository.clampOfferedAmountToStock(itemId, stock);
    materialExchangeOfferRepository.clampItemQuantityToStock(itemId, (int) Math.floor(stock));
  }

  /**
   * Deletes every non-personal inventory item in one bulk statement (the admin "globales Lager
   * leeren" action). Personal rows are kept.
   *
   * @return number of inventory rows deleted (0 if the global inventory was already empty)
   */
  @Transactional
  public int deleteAllGlobalInventory() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    log.info(
        "Bulk delete of global inventory requested (adminAll={}, active={}, members={})",
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds().size());
    int removed =
        inventoryItemRepository.deleteAllNonPersonal(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
    log.info(
        "Bulk delete of global inventory completed: {} item(s) removed (adminAll={}, active={})",
        removed,
        scope.adminAllScope(),
        scope.activeOrgUnitId());
    auditService.record(
        AuditEventType.INVENTORY_WIPED,
        null,
        null,
        null,
        AuditDetails.of(
                "scope", scope.adminAllScope() ? "adminAll" : "active=" + scope.activeOrgUnitId())
            .with("removed", removed));
    return removed;
  }

  /**
   * Deletes all listed inventory items owned by the caller in one batch, after locking and
   * ownership-checking each row. Their earmarks are removed by cascade.
   *
   * @param request the bulk checkout request containing item IDs
   * @param currentUserId the UUID of the authenticated user (JWT sub)
   */
  @Transactional
  public void bulkCheckout(@NotNull BulkCheckoutRequest request, UUID currentUserId) {
    log.info(
        "Bulk checkout requested by user {} for {} items", currentUserId, request.itemIds().size());

    List<UUID> toDelete = new ArrayList<>();

    for (UUID itemId : request.itemIds()) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.findByIdForUpdate(itemId),
              () -> "Inventory item not found: " + itemId);

      if (!item.getUser().getId().equals(currentUserId)) {
        log.warn(
            "User {} attempted to bulk-checkout item {} owned by {}",
            currentUserId,
            itemId,
            item.getUser().getId());
        throw new AccessDeniedException(
            "You are not allowed to check out inventory item: " + itemId);
      }

      toDelete.add(itemId);
    }

    inventoryItemRepository.deleteAllById(toDelete);
    log.info(
        "Bulk checkout completed: {} items removed for user {}", toDelete.size(), currentUserId);
    auditService.record(
        AuditEventType.INVENTORY_BULK_CHECKED_OUT,
        null,
        null,
        currentUserId,
        AuditDetails.of("count", toDelete.size()));
  }

  /**
   * Moves every listed row of the caller's inventory in full, to another location or owner or
   * across the personal marker (Massen-Umbuchen, REQ-INV-036). Moved rows keep all their earmarks.
   *
   * <p>Rows already in the target state are skipped and counted; any other obstacle aborts the
   * whole transaction. Rows are locked pessimistically in sorted id order and validated before the
   * first write.
   *
   * @param request the selection, the mode and the mode's target fields
   * @param currentUserId the authenticated caller's user id; every listed row must belong to them
   * @return how many rows moved and how many were skipped as already-at-target
   * @throws NotFoundException when an id is unknown, or the target user / location does not exist
   * @throws AccessDeniedException when a listed row belongs to another user
   * @throws BadRequestException when a {@code LOCATION} rebooking carries neither a target user nor
   *     a target location, or when a {@code PERSONALIZE} selection contains a row earmarked for a
   *     job order or mission
   */
  @NotNull
  @Transactional
  public BulkRebookResultDto bulkRebook(@NotNull BulkRebookRequest request, UUID currentUserId) {
    log.info(
        "Bulk rebook ({}) requested by user {} for {} items",
        request.mode(),
        currentUserId,
        request.itemIds().size());

    final List<InventoryItem> rows = loadOwnRowsForRebook(request.itemIds(), currentUserId);
    final User owner = rows.getFirst().getUser();

    final boolean mergeStock = Boolean.TRUE.equals(request.mergeStock());
    final BulkRebookResultDto result =
        switch (request.mode()) {
          case LOCATION -> bulkRebookToTarget(rows, request, owner, mergeStock);
          case PERSONALIZE -> bulkRebookPersonalMarker(rows, request, true, mergeStock);
          case DEPERSONALIZE -> bulkRebookPersonalMarker(rows, request, false, mergeStock);
        };

    log.info(
        "Bulk rebook ({}) completed for user {}: {} rebooked, {} skipped",
        request.mode(),
        currentUserId,
        result.rebooked(),
        result.skipped());
    if (result.rebooked() > 0) {
      auditService.record(
          AuditEventType.INVENTORY_BULK_REBOOKED,
          null,
          null,
          currentUserId,
          AuditDetails.of("mode", request.mode())
              .with("rebooked", result.rebooked())
              .with("skipped", result.skipped()));
    }
    return result;
  }

  /**
   * Phase one of {@link #bulkRebook}: loads every distinct listed row under a pessimistic write
   * lock in sorted id order and asserts the caller owns it, before any write happens.
   *
   * @param itemIds the requested ids (may contain duplicates; deduplicated here)
   * @param currentUserId the authenticated caller's user id
   * @return the locked, owned rows in sorted id order; never empty (the DTO validates non-empty)
   * @throws NotFoundException when an id is unknown
   * @throws AccessDeniedException when a row belongs to another user
   */
  @NotNull
  private List<InventoryItem> loadOwnRowsForRebook(
      @NotNull List<UUID> itemIds, UUID currentUserId) {
    final List<UUID> orderedIds = itemIds.stream().distinct().sorted().toList();
    final List<InventoryItem> rows = new ArrayList<>(orderedIds.size());
    for (UUID itemId : orderedIds) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.findByIdForRebook(itemId),
              () -> "Inventory item not found: " + itemId);
      if (!item.getUser().getId().equals(currentUserId)) {
        log.warn(
            "User {} attempted to bulk-rebook item {} owned by {}",
            currentUserId,
            itemId,
            item.getUser().getId());
        throw new AccessDeniedException("You are not allowed to rebook inventory item: " + itemId);
      }
      rows.add(item);
    }
    return rows;
  }

  /**
   * The {@link BulkRebookMode#LOCATION} branch of {@link #bulkRebook}: moves every row to the
   * requested target user / location, keeping each row's own value for whichever of the two the
   * request left blank. Rows already sitting at the target are skipped.
   *
   * @param rows the locked, owned source rows
   * @param request the bulk request (read for the two targets and the org-unit pick)
   * @param owner the rows' owner, the membership gate when the request keeps the current owner
   * @param mergeStock the per-action stock-merge opt-in
   * @return the moved / skipped counts
   * @throws BadRequestException when neither a target user nor a target location was given
   * @throws NotFoundException when the target user or location is unknown
   */
  @NotNull
  private BulkRebookResultDto bulkRebookToTarget(
      List<InventoryItem> rows, BulkRebookRequest request, User owner, boolean mergeStock) {
    if (request.targetUserId() == null && request.targetLocationId() == null) {
      throw new BadRequestException("Bulk transfer requires a target user or a target location");
    }
    final User targetUser =
        request.targetUserId() == null
            ? null
            : Entities.require(
                userRepository.findById(request.targetUserId()), "Target user not found");
    final Location targetLocation =
        request.targetLocationId() == null
            ? null
            : Entities.require(
                locationRepository.findById(request.targetLocationId()),
                "Target location not found");

    final OrgUnit targetOwningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(
            targetUser != null ? targetUser : owner, request.targetOwningOrgUnitId());

    int rebooked = 0;
    int skipped = 0;
    for (InventoryItem item : rows) {
      final User rowTargetUser = targetUser != null ? targetUser : item.getUser();
      final Location rowTargetLocation =
          targetLocation != null ? targetLocation : item.getLocation();
      if (isSameStackTarget(item, rowTargetUser, rowTargetLocation)) {
        skipped++;
        continue;
      }
      rebookWholeRow(
          item,
          rowTargetUser,
          rowTargetLocation,
          targetOwningOrgUnit,
          Boolean.TRUE.equals(item.getPersonal()),
          mergeStock);
      rebooked++;
    }
    return new BulkRebookResultDto(rebooked, skipped);
  }

  /**
   * Whether a row already sits at the requested transfer target, so a {@code LOCATION} bulk
   * rebooking skips it.
   *
   * @param item the source row
   * @param targetUser the row's resolved destination owner (never {@code null})
   * @param targetLocation the row's resolved destination location (may be {@code null} on a row
   *     without one)
   * @return {@code true} iff neither the owner nor the location would change
   */
  private static boolean isSameStackTarget(
      @NotNull InventoryItem item, @NotNull User targetUser, Location targetLocation) {
    final boolean sameUser = targetUser.getId().equals(item.getUser().getId());
    final boolean sameLocation =
        targetLocation == null
            ? item.getLocation() == null
            : item.getLocation() != null
                && targetLocation.getId().equals(item.getLocation().getId());
    return sameUser && sameLocation;
  }

  /**
   * The {@link BulkRebookMode#PERSONALIZE} / {@link BulkRebookMode#DEPERSONALIZE} branch of {@link
   * #bulkRebook}: moves every row to the requested personal state, skipping rows already there.
   * Personalizing is refused for the whole selection when any row carries an earmark.
   *
   * @param rows the locked, owned source rows
   * @param request the bulk request (read for the org-unit pick)
   * @param targetPersonal {@code true} to personalize, {@code false} to move into the shared pool
   * @param mergeStock the per-action stock-merge opt-in
   * @return the moved / skipped counts
   * @throws BadRequestException when personalizing a selection that contains an earmarked row
   */
  @NotNull
  private BulkRebookResultDto bulkRebookPersonalMarker(
      @NotNull List<InventoryItem> rows,
      BulkRebookRequest request,
      boolean targetPersonal,
      boolean mergeStock) {
    final List<InventoryItem> movable =
        rows.stream()
            .filter(row -> Boolean.TRUE.equals(row.getPersonal()) != targetPersonal)
            .toList();
    if (targetPersonal) {
      final long earmarked =
          movable.stream()
              .filter(
                  row ->
                      !row.getJobOrderAllocations().isEmpty()
                          || !row.getMissionAllocations().isEmpty())
              .count();
      if (earmarked > 0) {
        throw new BadRequestException(
            "Stock assigned to a job order or mission cannot be marked personal: "
                + earmarked
                + " of the selected entries are assigned");
      }
    }

    final OrgUnit sharedTargetOrgUnit =
        targetPersonal || movable.isEmpty()
            ? null
            : ownerScopeService.resolveOrgUnitForPickerOutputNullable(
                movable.getFirst().getUser(), request.targetOwningOrgUnitId());

    for (InventoryItem item : movable) {
      rebookWholeRow(
          item,
          item.getUser(),
          item.getLocation(),
          targetPersonal ? item.getOwningOrgUnit() : sharedTargetOrgUnit,
          targetPersonal,
          mergeStock);
    }
    return new BulkRebookResultDto(movable.size(), rows.size() - movable.size());
  }

  /**
   * Moves one row of a bulk rebooking in full: inserts a target row with the source's whole
   * quantity and earmarks, optionally merges it via {@link #mergeStockIfRequested}, and deletes the
   * source.
   *
   * @param source the locked source row
   * @param targetUser the destination owner
   * @param targetLocation the destination location
   * @param targetOwningOrgUnit the pool to stamp onto the new row, or {@code null} for an ownerless
   *     row
   * @param targetPersonal the personal marker the new row carries
   * @param mergeStock the per-action stock-merge opt-in
   */
  private void rebookWholeRow(
      @NotNull InventoryItem source,
      User targetUser,
      Location targetLocation,
      OrgUnit targetOwningOrgUnit,
      boolean targetPersonal,
      boolean mergeStock) {
    final double amount = source.getAmount() != null ? source.getAmount() : 0.0;

    InventoryItem newItem = new InventoryItem();
    newItem.setUser(targetUser);
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(source.getMaterial());
    newItem.setGameItem(source.getGameItem());
    newItem.setLocation(targetLocation);
    newItem.setQuality(source.getQuality());
    newItem.setAmount(InventoryItem.roundToScuScale(amount));
    newItem.setPersonal(targetPersonal);
    newItem.setNote(source.getNote());

    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(source, null, amount, true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(source, null, amount, false);
    applyTransferInherit(source, newItem, orderReductions, missionReductions);
    final InventoryItem savedNew = inventoryItemRepository.save(newItem);
    inventoryItemRepository.delete(source);

    mergeStockIfRequested(savedNew, mergeStock);
  }

  /**
   * Updates the delivered status of an inventory item. Applies optimistic locking via the version
   * field.
   *
   * @param id the UUID of the inventory item
   * @param request the update request containing delivered flag and version
   * @param currentUserId the UUID of the authenticated user
   * @param isLogistician whether the user has logistician or higher role
   * @return updated {@link InventoryItemDto}
   * @throws NotFoundException when the item is unknown
   */
  @Transactional
  public InventoryItemDto updateDelivered(
      UUID id, UpdateDeliveredRequest request, UUID currentUserId, boolean isLogistician) {
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findByIdForAllocationWrite(id), "Inventory item not found");

    if (!item.getUser().getId().equals(currentUserId) && !isLogistician) {
      throw new AccessDeniedException("You are not allowed to update this inventory item");
    }

    OptimisticLock.check(item.getVersion(), request.version(), InventoryItem.class, id);

    InventoryJobOrderAllocation slice =
        Entities.require(
            item.getJobOrderAllocations().stream()
                .filter(
                    a ->
                        a.getJobOrder() != null
                            && request.jobOrderId().equals(a.getJobOrder().getId()))
                .findFirst(),
            "Job-order allocation not found for this inventory item");
    slice.setDelivered(request.delivered());
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditService.record(
        AuditEventType.INVENTORY_ITEM_DELIVERY_TOGGLED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("delivered", request.delivered())
            .with("jobOrder", "#" + slice.getJobOrder().getDisplayId()));
    return inventoryItemMapper
        .toDto(saved)
        .withVersion(InventoryAllocations.forcedNextVersion(saved));
  }
}
