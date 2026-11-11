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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockSoldForTarget;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryNotices;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.TransferredLot;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryTransferNoticeService.TransferMove;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates single-row checkout, transfers and rebooking. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryCheckoutService {
  private static final double QUANTITY_EPSILON = 1e-4;
  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final StockSoldForTarget stockSoldForTarget;
  private final InventoryEarmarkLookup earmarkTargets;
  private final StockChangeObserver stockChangeObserver;
  private final InventoryItemMapper inventoryItemMapper;
  private final RequestScopeResolver requestScopeResolver;
  private final OrgUnitStampingService orgUnitStampingService;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final InventoryMergeService mergeService;
  private final InventoryBulkRebookService bulkService;
  private final InventoryTransferNoticeService transferNotices;

  /**
   * Discards, transfers or sells part of an inventory item, deleting the row when the remainder
   * falls below {@link #QUANTITY_EPSILON}.
   *
   * <p>A {@code null} type is inferred: {@code TRANSFER} when a target user or location is given,
   * otherwise {@code DISCARD}. A transfer inserts a new row at the target; a sale books mission
   * income.
   *
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the amount
   *     exceeds the stock, is fractional on a whole-unit row without depleting it, a sale lacks its
   *     terminal or amount, or a {@code TRANSFER} has no target
   */
  @Nullable
  @Transactional
  public InventoryItemDto bookOutInventoryItem(
      UUID id, InventoryItemBookOutDto dto, UUID currentUserId, boolean isAdmin) {
    return bookOut(id, dto, currentUserId, isAdmin, null, null).item();
  }

  /**
   * Books out part of a member's row for a connected application as {@link #bookOutInventoryItem}
   * does, auditing the Materialbörse offers it lowers or removes with the reason {@code stock}
   * (REQ-XCH-016).
   *
   * @param id the row
   * @param dto the book-out
   * @param memberId the member who owns the row
   * @return the offers the book-out lowered and removed
   * @throws NotFoundException when the row is unknown
   * @throws BadRequestException when the book-out is invalid for the row
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public StockChangeEffects bookOutForClient(UUID id, InventoryItemBookOutDto dto, UUID memberId) {
    return bookOut(id, dto, memberId, false, StockChangeReason.STOCK, null).offers();
  }

  /**
   * Books out part of a shared row for a Logistician of its pool through a connected application,
   * exactly as {@link #bookOutInventoryItem} does for a Logistician in the web (REQ-XCH-045).
   *
   * @param id the row
   * @param dto the book-out
   * @param actorId the writer, recorded as the actor
   * @return the offers the book-out lowered and removed
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  StockChangeEffects bookOutForPool(UUID id, InventoryItemBookOutDto dto, UUID actorId) {
    return bookOut(id, dto, actorId, true, null, null).offers();
  }

  /**
   * Transfers part of a shared row for a Logistician of its pool through a connected application,
   * as {@link #bookOutInventoryItem} does with {@code TRANSFER}, into the given pool instead of the
   * web's org-unit pick (REQ-XCH-045).
   *
   * @param id the row
   * @param dto the transfer, of type {@code TRANSFER}
   * @param actorId the writer, recorded as the actor
   * @param targetPool the org unit whose pool receives the moved stock
   * @return the moved stock's row after any merge, and the source's offer effects
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  PoolTransfer transferForPool(
      UUID id, InventoryItemBookOutDto dto, UUID actorId, @NotNull OrgUnit targetPool) {
    BookOut done = bookOut(id, dto, actorId, true, null, targetPool);
    return new PoolTransfer(Objects.requireNonNull(done.item()).id(), done.offers());
  }

  /**
   * Books out part of a row and keeps its Materialbörse offers in step.
   *
   * @param id the row
   * @param dto the book-out
   * @param currentUserId the caller
   * @param isAdmin whether the caller may book out another member's row
   * @param offerReason the audit reason of the offer effects, or {@code null} for the checkout
   *     type's
   * @param targetPool the pool a transfer moves into, or {@code null} for the org-unit pick
   * @return the remaining row, or {@code null} for a depleted discard or sale, and the offer
   *     effects
   */
  @NotNull
  private BookOut bookOut(
      UUID id,
      InventoryItemBookOutDto dto,
      UUID currentUserId,
      boolean isAdmin,
      @Nullable StockChangeReason offerReason,
      @Nullable OrgUnit targetPool) {
    InventoryItem item =
        Entities.require(inventoryItemRepository.findById(id), "Inventory item not found");

    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);

    if (!item.getUser().getId().equals(currentUserId) && !isAdmin) {
      throw new AccessDeniedException("You are not allowed to book out this inventory item");
    }

    InventoryStockDecisions.BookOutRefusal refusal =
        InventoryStockDecisions.bookOut(
            item,
            dto.amount(),
            dto.missionReductions() != null && !dto.missionReductions().isEmpty());
    if (refusal != null) {
      throw bookOutProblem(item, refusal);
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

    double remainingAmount = ScuAmounts.roundToScuScale(item.getAmount() - dto.amount());

    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = InventoryAuditLabels.catalogName(item);
    final UUID sourceOwnerId = item.getUser().getId();
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;
    List<UUID> financeEntryIds = List.of();

    if (checkoutType == CheckoutType.TRANSFER) {
      return bookOutTransfer(
          item,
          dto,
          currentUserId,
          remainingAmount,
          sourceId,
          sourceLabel,
          materialName,
          depleted,
          offerReason != null ? offerReason : StockChangeReason.TRANSFER,
          targetPool);
    }
    final StockChangeReason reason = offerReason != null ? offerReason : StockChangeReason.CHECKOUT;
    final TransferredLot bookedOutLot =
        new TransferredLot(
            materialName,
            dto.amount(),
            item.countsWholeUnits(),
            item.getQuality(),
            item.getLocation() != null ? item.getLocation().getName() : null);

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

    LotRows.Lowered lowered =
        LotRows.lower(inventoryItemRepository, stockChangeObserver, item, remainingAmount, reason);
    recordBookOutTail(
        checkoutType,
        sourceId,
        sourceLabel,
        materialName,
        sourceOwnerId,
        dto,
        lowered.depleted() ? 0.0 : remainingAmount,
        financeEntryIds);
    announceBookedOutByOther(checkoutType, sourceId, sourceOwnerId, currentUserId, bookedOutLot);
    return new BookOut(
        lowered.depleted() ? null : inventoryItemMapper.toDto(lowered.row()), lowered.offers());
  }

  /**
   * Maps a book-out refusal to the web's problem.
   *
   * @param item the row the book-out was refused on
   * @param refusal the refusal
   * @return the 400 problem to throw
   */
  private static @NotNull BadRequestException bookOutProblem(
      @NotNull InventoryItem item, @NotNull InventoryStockDecisions.BookOutRefusal refusal) {
    return new BadRequestException(
        switch (refusal) {
          case EXCEEDS_STOCK -> "Cannot book out more than the available amount";
          case FRACTIONAL_AMOUNT -> item.wholeUnitAmountDetail();
          case MISSION_EARMARK_ON_ITEM_STOCK -> "Game-item stock carries no mission earmarks";
        });
  }

  /**
   * Books out a {@code TRANSFER}: inserts a new row for {@code dto.amount()} at the target,
   * decrements or deletes the source, records the audit event and announces an owner change to the
   * new and the previous owner (REQ-INV-055).
   *
   * @param item the managed source row
   * @param dto the book-out request (target user/location/org-unit and amount)
   * @param actorId the member making the transfer
   * @param remainingAmount the source's post-decrement amount (already rounded)
   * @param sourceId the source row id snapshot
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param depleted whether the source row depletes to zero
   * @param offerReason the audit reason of the source's offer effects
   * @param targetPool the pool to stamp onto the new row, or {@code null} for the org-unit pick
   * @return the DTO of the newly created target row, and the source's offer effects
   * @throws NotFoundException when the target user or location is unknown
   * @throws BadRequestException when the transfer changes neither user nor location
   */
  @NotNull
  private BookOut bookOutTransfer(
      @NotNull InventoryItem item,
      InventoryItemBookOutDto dto,
      @NotNull UUID actorId,
      double remainingAmount,
      UUID sourceId,
      String sourceLabel,
      String materialName,
      boolean depleted,
      @NotNull StockChangeReason offerReason,
      @Nullable OrgUnit targetPool) {
    final User sourceUser = item.getUser();
    final Location sourceLocation = item.getLocation();
    User targetUser = item.getUser();
    if (dto.targetUserId() != null && !dto.targetUserId().equals(item.getUser().getId())) {
      targetUser =
          Entities.require(userRepository.findById(dto.targetUserId()), "Target user not found");
    }

    Location targetLocation = item.getLocation();
    if (dto.targetLocationId() != null
        && !dto.targetLocationId().equals(item.getLocation().getId())) {
      targetLocation =
          OfferedLocations.require(
              locationRepository.findById(dto.targetLocationId()), "Target location not found");
    }

    if (targetUser.getId().equals(item.getUser().getId())
        && targetLocation.getId().equals(item.getLocation().getId())) {
      throw new BadRequestException("error.inventory.transfer.unchanged");
    }

    final OrgUnit targetOwningOrgUnit =
        targetPool != null
            ? targetPool
            : orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
                targetUser, dto.targetOwningOrgUnitId());

    InventoryItem newItem =
        LotRows.split(
            item,
            dto.amount(),
            LotRows.Placement.of(item)
                .withUser(targetUser)
                .withLocation(targetLocation)
                .withOwningOrgUnit(targetOwningOrgUnit));
    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.jobOrderReductions(), dto.amount(), true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.missionReductions(), dto.amount(), false);
    InventoryAllocations.applyTransferInherit(item, newItem, orderReductions, missionReductions);
    final InventoryItem savedNew = inventoryItemRepository.save(newItem);
    AllocationReductions.applyPlan(item, orderReductions, true);
    AllocationReductions.applyPlan(item, missionReductions, false);
    final StockChangeEffects offers =
        LotRows.lower(
                inventoryItemRepository, stockChangeObserver, item, remainingAmount, offerReason)
            .offers();
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_TRANSFERRED,
        sourceId,
        sourceLabel,
        targetUser.getId(),
        AuditDetails.of("material", materialName)
            .with("amount", dto.amount())
            .with("toLoc", targetLocation != null ? targetLocation.getName() : "—")
            .with("newRow", newItem.getId())
            .with("depleted", depleted));
    transferNotices.publishTransferNotifications(
        actorId,
        List.of(
            TransferMove.of(
                item,
                sourceId,
                sourceUser,
                targetUser,
                ScuAmounts.roundToScuScale(dto.amount().doubleValue()),
                sourceLocation,
                targetLocation)));
    final InventoryItem mergedTarget =
        mergeStockIfRequested(savedNew, Boolean.TRUE.equals(dto.mergeStock()));
    return new BookOut(inventoryItemMapper.toDto(mergedTarget), offers);
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

    List<StockSoldForTarget.MissionShare> shares = new ArrayList<>();
    for (Map.Entry<UUID, Double> reduction : missionReductions.entrySet()) {
      InventoryMissionAllocation slice =
          InventoryAllocations.missionSlice(item, reduction.getKey());
      if (slice == null || slice.getMissionId() == null) {
        continue;
      }
      BigDecimal credit =
          proceeds
              .multiply(BigDecimal.valueOf(reduction.getValue()))
              .divide(totalSold, 4, RoundingMode.HALF_UP);
      if (credit.signum() <= 0) {
        continue;
      }
      shares.add(new StockSoldForTarget.MissionShare(slice.getMissionId(), credit));
    }
    return stockSoldForTarget.soldForMissions(
        currentUserId,
        shares,
        "Sale of "
            + dto.amount()
            + "x "
            + InventoryAuditLabels.catalogName(item)
            + " at "
            + dto.terminal());
  }

  /**
   * Tells the owner of a row that somebody else discarded or sold part of it (REQ-INV-056). Nothing
   * happens when the owner acted.
   *
   * @param type the resolved checkout type, {@code DISCARD} or {@code SELL}
   * @param sourceId the booked-out row
   * @param ownerId the row owner's id
   * @param actorId the member who booked out
   * @param lot the lot the action took
   */
  private void announceBookedOutByOther(
      @NotNull CheckoutType type,
      @NotNull UUID sourceId,
      @NotNull UUID ownerId,
      @NotNull UUID actorId,
      @NotNull TransferredLot lot) {
    if (ownerId.equals(actorId)) {
      return;
    }
    eventPublisher.publishEvent(
        InventoryNotices.bookedOutByOther(
            ownerId,
            sourceId,
            new ActorRef(actorId, transferNotices.actorName(actorId, List.of())),
            type == CheckoutType.SELL ? "SOLD" : "DISCARDED",
            List.of(lot)));
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
      auditRecorder.record(
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
      auditRecorder.record(
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

    InventoryStockDecisions.PersonalRebookRefusal refusal =
        InventoryStockDecisions.rebookPersonal(item, dto.amount());
    if (refusal != null) {
      throw new BadRequestException(
          switch (refusal) {
            case AMOUNT_NOT_POSITIVE -> "Rebooked amount must be positive";
            case EXCEEDS_STOCK -> "Cannot rebook more than the available amount";
            case FRACTIONAL_AMOUNT -> item.wholeUnitAmountDetail();
            case EARMARKED -> "Stock assigned to a job order or mission cannot be marked personal";
          });
    }

    final boolean targetPersonal = !Boolean.TRUE.equals(item.getPersonal());

    final OrgUnit targetOwningOrgUnit =
        targetPersonal
            ? item.getOwningOrgUnit()
            : orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
                item.getUser(), dto.targetOwningOrgUnitId());

    final boolean merge = Boolean.TRUE.equals(dto.mergeStock());
    return inventoryItemMapper.toDto(
        rebookRow(item, dto.amount(), targetPersonal, targetOwningOrgUnit, merge));
  }

  /**
   * Splits an amount of a row into a new row of its holder and lot with the given personal marker
   * and org unit (REQ-INV-007), lowering or deleting the source, auditing the move and merging the
   * new row (REQ-INV-026); the new row carries no earmark and no note.
   *
   * @param item the managed source row
   * @param amount the amount to move, at most the row's
   * @param targetPersonal the personal marker of the new row
   * @param targetOwningOrgUnit the org unit of the new row, or {@code null}
   * @param mergeStock the merge opt-in of an SCU material
   * @return the row that holds the moved stock, after any merge
   * @throws OverAllocationException when the source's earmarks no longer fit what it keeps
   */
  @Transactional(propagation = Propagation.MANDATORY)
  InventoryItem rebookRow(
      @NotNull InventoryItem item,
      double amount,
      boolean targetPersonal,
      @Nullable OrgUnit targetOwningOrgUnit,
      boolean mergeStock) {
    final double remainingAmount = ScuAmounts.roundToScuScale(item.getAmount() - amount);
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;

    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = InventoryAuditLabels.catalogName(item);
    final UUID ownerId = item.getUser().getId();

    InventoryItem newItem =
        LotRows.split(
            item,
            amount,
            LotRows.Placement.of(item)
                .withOwningOrgUnit(targetOwningOrgUnit)
                .withPersonal(targetPersonal));
    InventoryItem savedNew = inventoryItemRepository.save(newItem);
    LotRows.lower(
        inventoryItemRepository,
        stockChangeObserver,
        item,
        remainingAmount,
        StockChangeReason.REBOOK);

    auditRecorder.record(
        targetPersonal
            ? AuditEventType.INVENTORY_ITEM_PERSONALIZED
            : AuditEventType.INVENTORY_ITEM_DEPERSONALIZED,
        sourceId,
        sourceLabel,
        ownerId,
        AuditDetails.of("material", materialName)
            .with("amount", amount)
            .with("newRow", newItem.getId())
            .with("targetOrgUnit", targetOwningOrgUnit != null ? targetOwningOrgUnit.getId() : "-")
            .with("depleted", depleted));

    return mergeStockIfRequested(savedNew, mergeStock);
  }

  /**
   * Deletes every non-personal inventory item in the caller's scope (the admin "globales Lager
   * leeren" action). Personal rows are kept.
   *
   * <p>The rows are locked first, so no Materialbörse offer can be released on or withdrawn from
   * them between the offer audit and the delete; exactly the locked rows are deleted, and a row
   * booked into the scope meanwhile survives.
   *
   * @return number of inventory rows deleted (0 if the global inventory was already empty)
   */
  @Transactional
  public int deleteAllGlobalInventory() {
    ScopePredicate scope = requestScopeResolver.currentScopePredicate();
    log.info(
        "Bulk delete of global inventory requested (adminAll={}, active={}, members={})",
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds().size());
    List<UUID> wiped =
        inventoryItemRepository.lockNonPersonalIdsInScope(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds());
    stockChangeObserver.beforeDelete(wiped, StockChangeReason.WIPE);
    int removed = inventoryItemRepository.deleteAllByIdIn(wiped);
    log.info(
        "Bulk delete of global inventory completed: {} item(s) removed (adminAll={}, active={})",
        removed,
        scope.adminAllScope(),
        scope.activeOrgUnitId());
    auditRecorder.record(
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
                .filter(a -> request.jobOrderId().equals(a.getJobOrderId()))
                .findFirst(),
            "Job-order allocation not found for this inventory item");
    slice.setDelivered(request.delivered());
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_DELIVERY_TOGGLED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("delivered", request.delivered())
            .with(
                "jobOrder",
                "#"
                    + Objects.requireNonNull(earmarkTargets.jobOrder(slice.getJobOrderId()))
                        .displayId()));
    return inventoryItemMapper
        .toDto(saved)
        .withVersion(InventoryAllocations.forcedNextVersion(saved));
  }

  /**
   * The outcome of one book-out.
   *
   * @param item the remaining source row, the new target row of a transfer, or {@code null} for a
   *     depleted discard or sale
   * @param offers the Materialbörse offers the book-out lowered and removed
   */
  private record BookOut(@Nullable InventoryItemDto item, @NotNull StockChangeEffects offers) {}

  /**
   * The outcome of a transfer into a pool.
   *
   * @param targetId the row that holds the moved stock, after any merge
   * @param offers the Materialbörse offers the transfer lowered and removed on the source
   */
  record PoolTransfer(@NotNull UUID targetId, @NotNull StockChangeEffects offers) {}

  /**
   * Folds every other row with the same physical stock identity into {@code row} (REQ-INV-026):
   * amounts summed, distinct notes concatenated, earmarks unioned, siblings deleted.
   *
   * <p>Always applies to {@code PIECE} materials and game items; for {@code SCU} only when {@code
   * clientRequestedMerge} is set. The group is locked {@code FOR UPDATE}, and rows backing a
   * Materialbörse offer are never changed or folded; the offers are checked again once the group is
   * locked, since an offer released while the lock waited is not excluded by the locking read. Must
   * run inside the caller's transaction.
   *
   * @param row the just-written target row (managed); the merge survivor
   * @param clientRequestedMerge the per-action opt-in for an {@code SCU} material (ignored for
   *     {@code PIECE} materials and game-item rows)
   * @return {@code row}, merged or unchanged
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InventoryItem mergeStockIfRequested(
      @NotNull InventoryItem row, boolean clientRequestedMerge) {
    return mergeService.mergeStockIfRequested(row, clientRequestedMerge);
  }

  /**
   * Deletes all listed inventory items owned by the caller in one batch, after locking,
   * ownership-checking and donation-checking each row. Their job-order and mission earmarks are
   * removed by cascade.
   *
   * @param request the bulk checkout request containing item IDs
   * @param currentUserId the UUID of the authenticated user (JWT sub)
   * @throws NotFoundException when an id is unknown
   * @throws AccessDeniedException when a listed row belongs to another user
   * @throws OverAllocationException when a listed row still carries a pledged donation
   *     (REQ-INV-058); nothing is removed
   */
  @Transactional
  public void bulkCheckout(@NotNull BulkCheckoutRequest request, UUID currentUserId) {
    bulkService.bulkCheckout(request, currentUserId);
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
    return bulkService.bulkRebook(request, currentUserId);
  }
}
