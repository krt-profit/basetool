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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the inventory aggregate — the checkout / book-out / rebook / bulk-mutation flows
 * that consume, move, sell or wipe squadron stock.
 *
 * <p>Extracted from {@code InventoryItemService} (#921, L2) as the mutating cluster of the former
 * god-class: the book-out flow ({@link #bookOutInventoryItem} with its per-type {@link
 * #bookOutTransfer} / {@link #createSaleFinanceEntries} branches and the {@link #recordBookOutTail}
 * audit tail), the personal↔shared rebooking ({@link #rebookPersonal}), the bulk checkout ({@link
 * #bulkCheckout}), the delivered-flag toggle ({@link #updateDelivered}) and the admin global-wipe
 * ({@link #deleteAllGlobalInventory}). {@code InventoryItemService} keeps the identical public
 * method signatures and delegates to this service, so controllers and callers are unchanged.
 *
 * <p>Concurrency-relevant (CLAUDE.md): inventory is <em>append-only</em> — book-out {@code
 * TRANSFER} and {@code rebookPersonal} always insert a new target row and decrement (or delete) the
 * source row, never folding into an existing stack, which removes the read-add-write race a merge
 * path would carry. {@link #bulkCheckout} follows the bulk-update-after-loop discipline: it clears
 * the job-order / mission associations on the managed rows inside the loop (Hibernate
 * dirty-checking, no {@code @Modifying} query inside the loop), flushes once, then deletes all ids
 * in a single batch. {@link #deleteAllGlobalInventory} is a one-shot bulk {@code DELETE} (the
 * load-bearing FK was dropped in {@code V64}), so no clearing loop is required. Partial book-outs /
 * rebookings {@code saveAndFlush} the reduced source row so its {@code @Version} stays current
 * within the transaction and a follow-up in-place edit cannot 409 (REQ-FE-003).
 *
 * <p>Each public method opens its own read-write {@code @Transactional} (the class carries no
 * class-level {@code readOnly} default), so a mutating method is never accidentally trapped in a
 * read-only transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryCheckoutService {

  /**
   * Tolerance used when comparing inventory amounts that are stored as {@code double}. Mirrors
   * {@code JobOrderHandoverService.QUANTITY_EPSILON} — both services compare the same quantity
   * column on {@link de.greluc.krt.profit.basetool.backend.model.InventoryItem} so they need the
   * same rounding-safe threshold. Anything below 1e-4 is floating-point noise (quantities are
   * user-edited at three decimals max), not a real residual.
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
   * Consumes or transfers an inventory item.
   *
   * <p>The {@code type} discriminator selects: DISCARD (just decrement), TRANSFER (decrement here,
   * then insert a new row for the moved quantity at the target location/owner — inventory is
   * append-only, so the moved stock is never folded into an existing target stack), SELL (decrement
   * here, create a finance entry for the participant). When {@code type} is {@code null} it is
   * inferred from the presence of a target (TRANSFER when a target user/location is given, else
   * DISCARD); an <em>explicit</em> {@code TRANSFER} carrying neither target is rejected up front so
   * it can never fall through and silently consume the source stock (REQ-INV-025). When the
   * post-decrement quantity is below {@link #QUANTITY_EPSILON} the row is removed entirely.
   *
   * @throws NotFoundException when the item is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the requested
   *     amount exceeds the available quantity, when a fractional amount is booked off a whole-unit
   *     row (PIECE material / item stock) without depleting it entirely, when a SELL is missing its
   *     terminal or a valid sell amount, or when a {@code TRANSFER} carries neither a target user
   *     nor a target location
   */
  @Transactional
  public InventoryItemDto bookOutInventoryItem(
      UUID id, InventoryItemBookOutDto dto, UUID currentUserId, boolean isAdmin) {
    InventoryItem item =
        inventoryItemRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));

    OptimisticLock.checkOptionalClient(item.getVersion(), dto.version(), InventoryItem.class, id);

    if (!item.getUser().getId().equals(currentUserId) && !isAdmin) {
      throw new AccessDeniedException("You are not allowed to book out this inventory item");
    }

    if (dto.amount() > item.getAmount()) {
      throw new BadRequestException("Cannot book out more than the available amount");
    }

    // Whole-unit book-out (design §5.1): game-item rows always hold whole units (REQ-INV-029),
    // and the same check closes the pre-existing gap that PIECE-material book-outs were not
    // whole-number-validated server-side. The rule lives here because the book-out DTO carries no
    // catalog reference, so it cannot be bean-validated. Booking out the row's exact remaining
    // amount is exempt: rows created before this guard existed may hold a fractional amount, and
    // without the full-depletion escape that remainder could never be drained at all.
    if (requiresWholeUnits(item)
        && dto.amount() % 1 != 0
        && Double.compare(dto.amount(), item.getAmount()) != 0) {
      throw new BadRequestException(wholeUnitAmountDetail(item));
    }

    // Game-item rows carry no mission dimension (REQ-INV-031): a mission "deduct from" plan on an
    // item row is a contract violation, not an empty no-op — reject it explicitly.
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
      if (dto.sellAmount() == null || dto.sellAmount().compareTo(java.math.BigDecimal.ZERO) < 0) {
        throw new BadRequestException("Sell amount is required and must be positive");
      }
    }

    // A TRANSFER with neither a target user nor a target location has nowhere to move the stock to.
    // Reject it up front (400) — an explicit type=TRANSFER survives the null-inference above, so
    // without this guard it would fall through to the consume tail below, silently destroying the
    // source stock and mislogging it as INVENTORY_ITEM_CONSUMED with type=TRANSFER (REQ-INV-025).
    if (checkoutType == CheckoutType.TRANSFER
        && dto.targetUserId() == null
        && dto.targetLocationId() == null) {
      throw new BadRequestException("Transfer requires a target user or location");
    }

    double remainingAmount = InventoryItem.roundToScuScale(item.getAmount() - dto.amount());

    // Snapshot the source row's scalar identity BEFORE any decrement/delete so the audit row stays
    // accurate even when the source is depleted to zero and removed (bulk-clear landmine rules).
    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = catalogName(item);
    final UUID sourceOwnerId = item.getUser().getId();
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;
    List<UUID> financeEntryIds = List.of();

    if (checkoutType == CheckoutType.TRANSFER) {
      // A TRANSFER is guaranteed to carry at least one target here (the up-front guard rejects the
      // target-less case), so the branch is unconditional on the type. It resolves + applies its
      // own
      // "deduct from" plan (and carries the reduced tags onto the moved row) inside
      // bookOutTransfer.
      return bookOutTransfer(
          item, dto, remainingAmount, sourceId, sourceLabel, materialName, depleted);
    }

    // Variante C (REQ-INV-027): resolve the "deduct from" plan for the two independent dimensions
    // against the pre-decrement slices. A null list defaults to "rest first, forced remainder
    // spread
    // across the tags"; an explicit list is validated (unknown / duplicate / over-slice /
    // over-total
    // => 400, an under-assigned plan whose rest cannot absorb the remainder => 422).
    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.jobOrderReductions(), dto.amount(), true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(
            item, dto.missionReductions(), dto.amount(), false);

    if (checkoutType == CheckoutType.SELL) {
      // Coupled proceeds (REQ-INV-027): each mission's credited income is proportional to the SCU
      // deducted from its earmark; the uncredited rest (unassigned + non-participated missions)
      // stays
      // the seller's personal proceeds. Read the mission slices for the credit BEFORE applying the
      // reductions, which shrink / remove them.
      financeEntryIds = createSaleFinanceEntries(item, dto, currentUserId, missionReductions);
    }
    // Apply the plan to the source's slices (shrinks / removes the tags); the amount is lowered
    // below.
    AllocationReductions.applyPlan(item, orderReductions, true);
    AllocationReductions.applyPlan(item, missionReductions, false);

    if (remainingAmount <= QUANTITY_EPSILON) { // Floating point precision safety
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
      // Variante C (REQ-INV-027, R5): a book-out does NOT auto-shrink the entry's earmarks. If the
      // reduced amount no longer covers them, block (422) so the user lowers the allocations first.
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      // saveAndFlush so a partial book-out's response carries the fresh @Version — otherwise a
      // follow-up edit of the reduced row 409s.
      InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
      // Ratchet any active Materialbörse offer on this row down to the reduced stock
      // (REQ-MARKET-013/014 — kind-aware for material and stock-backed item offers).
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
   * Applies the {@code TRANSFER} book-out branch: an append-only move of {@code dto.amount()} to
   * the resolved target user / location / owning-org-unit pool (a new row is inserted, never folded
   * into an existing target stack), decrementing the source row (deleting it when it depletes below
   * {@link #QUANTITY_EPSILON}), and records the transfer audit event.
   *
   * @param item the managed source row
   * @param dto the book-out request (target user/location/org-unit + amount)
   * @param remainingAmount the source's post-decrement amount (already rounded)
   * @param sourceId the source row id snapshot
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param depleted whether the source row depletes to zero (audit detail)
   * @return the DTO of the newly created target row
   * @throws NotFoundException when the target user or location is unknown
   * @throws BadRequestException when the transfer changes neither user nor location
   */
  private InventoryItemDto bookOutTransfer(
      InventoryItem item,
      InventoryItemBookOutDto dto,
      double remainingAmount,
      UUID sourceId,
      String sourceLabel,
      String materialName,
      boolean depleted) {
    User targetUser = item.getUser();
    if (dto.targetUserId() != null && !dto.targetUserId().equals(item.getUser().getId())) {
      targetUser =
          userRepository
              .findById(dto.targetUserId())
              .orElseThrow(() -> new NotFoundException("Target user not found"));
    }

    Location targetLocation = item.getLocation();
    if (dto.targetLocationId() != null
        && !dto.targetLocationId().equals(item.getLocation().getId())) {
      targetLocation =
          locationRepository
              .findById(dto.targetLocationId())
              .orElseThrow(() -> new NotFoundException("Target location not found"));
    }

    if (targetUser.getId().equals(item.getUser().getId())
        && targetLocation.getId().equals(item.getLocation().getId())) {
      throw new BadRequestException("Transfer must change either the user or the location");
    }

    // Resolve the target stack's owning org-unit pool up front — the eighth identity dimension —
    // so the freshly created target row is stamped with that pool.
    final OrgUnit targetOwningOrgUnit =
        ownerScopeService.resolveOrgUnitForPickerOutputNullable(
            targetUser, dto.targetOwningOrgUnitId());

    // Append-only: a transfer always inserts its own row at the target and is never folded into
    // an existing identical stack there. The view collapses rows that share a stack identity for
    // display (group-on-read), so no duplicate is visible and no read-add-write race exists.
    InventoryItem newItem = new InventoryItem();
    newItem.setUser(targetUser);
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(item.getMaterial());
    // Copy the catalog reference pair as a unit (design §4.4): without the gameItem the moved
    // item-row copy would violate the XOR CHECK (chk_inventory_item_catalog_xor, V220) → 500.
    newItem.setGameItem(item.getGameItem());
    newItem.setLocation(targetLocation);
    newItem.setQuality(item.getQuality());
    newItem.setAmount(InventoryItem.roundToScuScale(dto.amount()));
    newItem.setPersonal(item.getPersonal());
    // Variante C (REQ-INV-027, "Marken mitnehmen"): resolve the "deduct from" plan against the
    // source's pre-decrement slices, carry the reduced tags onto the moved row (each tag moves with
    // exactly the SCU deducted from it), then shrink the source's slices by the same plan. A full
    // move with no explicit plan defaults to "all slices in full", so the moved row inherits every
    // earmark; a partial move with no plan takes it all from the rest, leaving the moved row
    // unassigned and the source's tags intact (both the legacy behaviours).
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
      // R5 backstop: the plan already keeps Σ(dimension) ≤ the reduced amount by construction; the
      // fits() guard stays as defence in depth and 422s if that invariant were ever violated.
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      // saveAndFlush the reduced source row for parity with the discard/sell fall-through below:
      // the returned DTO is the new target row, but flushing keeps the source row's @Version
      // current within the transaction so any future in-place consumer of a transfer cannot 409.
      inventoryItemRepository.saveAndFlush(item);
      // Ratchet any active Materialbörse offer on the source row down to the reduced stock
      // (REQ-MARKET-013/014 — kind-aware for material and stock-backed item offers).
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
    // Merge the moved quantity into a matching target stack when it applies (PIECE always, SCU on
    // the per-action opt-in); the target row is the survivor, so its id/version stays stable.
    final InventoryItem mergedTarget =
        mergeStockIfRequested(savedNew, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(mergedTarget);
  }

  /**
   * Books the coupled per-mission income for a {@code SELL} book-out (Variante C, REQ-INV-027). The
   * proceeds follow the sale's mission "deduct from" plan: each mission the seller took sold SCU
   * out of is credited a share of {@code dto.sellAmount()} proportional to that SCU — {@code
   * sellAmount × deductedScu / totalSoldScu} — as one squadron {@code INCOME} {@link
   * MissionFinanceEntry}. The uncredited remainder (SCU taken from the mission rest, plus any
   * deducted from a mission the seller does not participate in) stays the seller's personal
   * proceeds; no separate money-attribution input exists. An empty plan (nothing deducted from a
   * mission earmark) is a fully-personal sale.
   *
   * <p>Read the mission slices BEFORE the reductions are applied (which shrinks / removes them). A
   * mission the seller does not participate in cannot receive a {@link MissionFinanceEntry} (it
   * structurally requires a {@link MissionParticipant}), so that share simply falls to personal
   * rather than being an error.
   *
   * @param item the managed source row (its mission allocations loaded within the tx)
   * @param dto the book-out request (read for the sell amount, terminal, total sold amount)
   * @param currentUserId the selling participant's user id
   * @param missionReductions the resolved mission plan (missionId → deducted SCU), unique per
   *     mission
   * @return the created finance-entry ids (read off the managed entities, so a unit-test mock's
   *     null {@code save()} return does not matter); empty for a fully-personal sale
   */
  private List<UUID> createSaleFinanceEntries(
      InventoryItem item,
      InventoryItemBookOutDto dto,
      UUID currentUserId,
      Map<UUID, Double> missionReductions) {
    BigDecimal totalSold = BigDecimal.valueOf(dto.amount() != null ? dto.amount() : 0.0);
    if (missionReductions.isEmpty() || totalSold.signum() <= 0) {
      // Nothing deducted from a mission earmark (or a degenerate zero sale) => fully-personal sale.
      return List.of();
    }
    BigDecimal proceeds = dto.sellAmount() != null ? dto.sellAmount() : BigDecimal.ZERO;

    // Build every entry BEFORE persisting any, so a mid-loop skip leaves the ledger consistent.
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
        // Sold SCU earmarked to a mission the seller is not part of => that share stays personal.
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
      // catalogName instead of a raw material dereference: unreachable for game-item rows today
      // (they carry no mission slices, REQ-INV-031), but the null-guard keeps the SELL tail safe
      // for every row kind (design §4.4 consumer null-branches).
      entry.setNote("Sale of " + dto.amount() + "x " + catalogName(item) + " at " + dto.terminal());
      entries.add(entry);
    }

    List<UUID> financeEntryIds = new ArrayList<>();
    for (MissionFinanceEntry entry : entries) {
      missionFinanceEntryRepository.save(entry);
      // Read the id off the managed entity (set by save()); the dedicated capture avoids relying on
      // the save() return value, which a unit-test mock leaves null.
      financeEntryIds.add(entry.getId());
    }
    return financeEntryIds;
  }

  /**
   * Carries the reduced tags of a transfer onto the moved row ("Marken mitnehmen", REQ-INV-027):
   * each planned reduction becomes a same-size earmark on {@code target}, inheriting the source
   * order slice's delivered flag. Must run BEFORE the reductions are applied, which shrinks the
   * source slices, since it reads their managed {@code JobOrder} / {@code Mission} and delivered
   * state.
   *
   * @param source the source row whose slices are read for the tags; never {@code null}
   * @param target the freshly built moved row to earmark; never {@code null}
   * @param orderReductions the job-order plan (orderId → SCU)
   * @param missionReductions the mission plan (missionId → SCU)
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
   * Records the audit event for the consume/sell tail of {@link #bookOutInventoryItem} (the
   * transfer branch records its own event). SELL events carry the
   * terminal/sell-amount/finance-entry; DISCARD events carry the consumed/remaining amounts.
   *
   * @param type the resolved checkout type (never {@code TRANSFER} here)
   * @param sourceId the source row id (snapshotted before a possible delete)
   * @param sourceLabel the source row's {@code material @ location} label snapshot
   * @param materialName the material name snapshot
   * @param ownerId the source row owner's id
   * @param dto the book-out request (read for terminal/sell amount)
   * @param remaining the post-decrement amount (0 when the row was depleted)
   * @param financeEntryIds the created per-mission finance entry ids for a SELL (empty for a
   *     fully-personal sale that credited no mission)
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
              .with("terminal", dto.terminal())
              .with("sellAmount", dto.sellAmount())
              .with(
                  "financeEntries",
                  financeEntryIds.stream()
                      .filter(id -> id != null)
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
   * Rebooks (Umbuchung) part or all of an inventory row between the owner's personal pool and the
   * shared squadron pool by toggling its {@code personal} marker (REQ-INV-007).
   *
   * <p>The direction is derived from the source row's current {@code personal} flag, never from the
   * caller: a {@code personal = true} source is <em>de-personalized</em> (the moved quantity
   * becomes shared squadron stock stamped on {@code dto.targetOwningOrgUnitId()}'s pool); a {@code
   * personal = false} source is <em>personalized</em> (the moved quantity becomes the owner's
   * private stock, carrying the source row's existing org-unit stamp over). Either way this is an
   * append-only split mirroring the book-out {@code TRANSFER} branch: the moved {@code amount} is
   * decremented off the source (the source row is deleted when it depletes below {@link
   * #QUANTITY_EPSILON}) and inserted as its own new row with the opposite {@code personal} flag —
   * never folded into an existing stack (REQ-INV-001).
   *
   * <p>The personalize direction refuses a source row bound to a job order or mission: a {@code
   * personal = true} row may never carry either association (the invariant {@code
   * InventoryItemService.createInventoryItem} and the allocation writes also enforce), and silently
   * dropping the link would lose the assignment.
   *
   * @param id the source inventory row id
   * @param dto the rebooking payload (amount, version, target org-unit pool)
   * @param currentUserId the authenticated caller's user id
   * @param isAdmin whether the caller holds an admin role (bypasses the owner check)
   * @return the persisted new-row DTO (the moved quantity in its new pool)
   * @throws NotFoundException when the source row or the picked org unit is unknown
   * @throws BadRequestException when the amount is non-positive, exceeds the available quantity, is
   *     fractional on a whole-unit row (PIECE material / item stock) without moving the row's exact
   *     remaining amount, or a personalize would violate the personal/association invariant
   */
  @Transactional
  public InventoryItemDto rebookPersonal(
      UUID id, InventoryItemPersonalRebookDto dto, UUID currentUserId, boolean isAdmin) {
    InventoryItem item =
        inventoryItemRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));

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
    // Whole-unit rebook (design §5.1): game-item rows and PIECE-material rows only ever move whole
    // units; the rule lives here because the rebook DTO carries no catalog reference. Moving the
    // row's exact remaining amount is exempt so a fractional remainder from before the guard
    // existed can still leave its pool instead of being stranded.
    if (requiresWholeUnits(item)
        && dto.amount() % 1 != 0
        && Double.compare(dto.amount(), item.getAmount()) != 0) {
      throw new BadRequestException(wholeUnitAmountDetail(item));
    }

    final boolean sourcePersonal = Boolean.TRUE.equals(item.getPersonal());
    final boolean targetPersonal = !sourcePersonal;

    // Personalize (shared -> personal): a personal row may never carry a job order or mission, so
    // refuse an assigned source rather than silently dropping the link.
    if (targetPersonal
        && (!item.getJobOrderAllocations().isEmpty() || !item.getMissionAllocations().isEmpty())) {
      throw new BadRequestException(
          "Stock assigned to a job order or mission cannot be marked personal");
    }

    // De-personalize stamps the new shared row on the picked org-unit pool (validated against the
    // owner's memberships); personalize carries the source row's existing stamp over to the private
    // row (personal visibility is owner-scoped regardless of the stamp).
    final OrgUnit targetOwningOrgUnit =
        targetPersonal
            ? item.getOwningOrgUnit()
            : ownerScopeService.resolveOrgUnitForPickerOutputNullable(
                item.getUser(), dto.targetOwningOrgUnitId());

    final double remainingAmount = InventoryItem.roundToScuScale(item.getAmount() - dto.amount());
    final boolean depleted = remainingAmount <= QUANTITY_EPSILON;

    // Snapshot the source row's scalar identity before any decrement/delete so the audit row stays
    // accurate even when the source is depleted to zero and removed.
    final UUID sourceId = item.getId();
    final String sourceLabel = InventoryAuditLabels.label(item);
    final String materialName = catalogName(item);
    final UUID ownerId = item.getUser().getId();

    // Append-only: insert a new row for the moved quantity with the flipped personal flag; the
    // grouped view collapses rows that share a stack identity for display (group-on-read), so no
    // duplicate is visible and no read-add-write race exists. A personal target never carries a job
    // order / mission association.
    InventoryItem newItem = new InventoryItem();
    newItem.setUser(item.getUser());
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(item.getMaterial());
    // Copy the catalog reference pair as a unit (design §4.4): without the gameItem the rebooked
    // item-row copy would violate the XOR CHECK (chk_inventory_item_catalog_xor, V220) → 500.
    newItem.setGameItem(item.getGameItem());
    newItem.setLocation(item.getLocation());
    newItem.setQuality(item.getQuality());
    newItem.setAmount(InventoryItem.roundToScuScale(dto.amount()));
    newItem.setPersonal(targetPersonal);
    // Variante C (REQ-INV-027): the moved row is unassigned — a personalize target carries no
    // earmark (personal stock never does), and a de-personalize source is itself personal so has no
    // allocations to inherit. Re-assign the shared result via chips.
    InventoryItem savedNew = inventoryItemRepository.save(newItem);

    if (depleted) {
      inventoryItemRepository.delete(item);
    } else {
      item.setAmount(remainingAmount);
      // R5: the source keeps its allocations (a personalize leaves the shared remainder earmarked);
      // block (422) if the reduced amount no longer covers them.
      if (!InventoryAllocations.fits(item)) {
        throw new OverAllocationException();
      }
      // saveAndFlush (not save) keeps the source row's @Version current within the transaction so a
      // follow-up in-place edit of the reduced row cannot 409 (REQ-FE-003 parity with book-out).
      inventoryItemRepository.saveAndFlush(item);
      // Ratchet any active Materialbörse offer on the source row down to the reduced stock
      // (REQ-MARKET-013/014 — kind-aware for material and stock-backed item offers).
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

    // Merge the rebooked quantity into a matching stack in its new pool when it applies (PIECE
    // always, SCU on the per-action opt-in); the new row is the survivor.
    final InventoryItem mergedTarget =
        mergeStockIfRequested(savedNew, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(mergedTarget);
  }

  /**
   * Folds a just-written warehouse row into a single merged stack when the merge applies
   * (REQ-INV-026), and returns the surviving row (or the unchanged input when nothing merges).
   *
   * <p>The merge runs <em>unconditionally</em> for a {@code PIECE} material and for a game-item row
   * (item stock follows the PIECE whole-unit auto-merge rule, REQ-INV-029 — the client flag is
   * irrelevant for both), and for an {@code SCU} material only when {@code clientRequestedMerge} is
   * {@code true} — the per-action modal opt-in the caller ticked for this single write; it is never
   * persisted. The survivor is the passed-in {@code row}: every other row that shares its
   * <em>physical</em> stock identity (Variante C, REQ-INV-027: user · catalog reference · location
   * · quality · personal · owningOrgUnit — the earmarks are NO longer part of the key; a game-item
   * stack keys on the game item with material and quality {@code NULL}) is folded into it — amounts
   * summed, distinct notes concatenated, their job-order / mission allocations unioned into the
   * survivor (summed per target, job-order delivered OR-combined, rule R1) — and deleted. The merge
   * group is loaded {@code FOR UPDATE} so two racing same-stack writers serialise (re-introducing,
   * only here, the lock the append-only model of ADR-0003 removed).
   *
   * <p><strong>Materialbörse safety:</strong> a row that itself backs an offer is returned
   * untouched (never a survivor that changes, never folded away), and offer-backed sibling rows are
   * excluded by the query. This honours "a merge never changes a Materialbörse entry" (REQ-MARKET)
   * and avoids the {@code ON DELETE CASCADE} FK (V210) silently destroying an offer.
   *
   * <p>Propagation is {@code MANDATORY}: this must join the caller's read-write transaction (the
   * create / update / transfer / rebook flow), never open its own.
   *
   * @param row the just-created / just-edited / just-inserted target row (managed); the merge
   *     survivor.
   * @param clientRequestedMerge the per-action opt-in for an {@code SCU} material (ignored for
   *     {@code PIECE} materials and game-item rows, which always merge).
   * @return the surviving merged row (== {@code row}) with the summed amount and combined notes, or
   *     {@code row} unchanged when the merge does not apply or finds no matching sibling.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public InventoryItem mergeStockIfRequested(InventoryItem row, boolean clientRequestedMerge) {
    final Material material = row.getMaterial();
    final boolean gameItemRow = row.getGameItem() != null;
    if (material == null && !gameItemRow) {
      // A row with neither catalog reference cannot participate in merge-group matching, so it
      // stays unchanged — consistent with append-only/no-merge behaviour. Returning here also
      // guards the material.getQuantityType() dereference below.
      return row;
    }
    // Game-item rows always auto-merge (PIECE rule, REQ-INV-029 — the client flag is irrelevant);
    // a material row merges unconditionally only for PIECE.
    final boolean autoMerge = gameItemRow || material.getQuantityType() == QuantityType.PIECE;
    if (!autoMerge && !clientRequestedMerge) {
      // SCU without the per-action opt-in stays append-only (REQ-INV-001).
      return row;
    }
    // Never merge stock the Materialbörse references: its offered quantity must stay untouched and
    // the ON DELETE CASCADE FK (V210) would destroy the offer if this row were folded away.
    // Matching
    // offer-backed siblings are excluded by the query's NOT EXISTS.
    if (materialExchangeOfferRepository.existsByInventoryItemId(row.getId())) {
      return row;
    }

    // Catalog-discriminated merge key (REQ-INV-029): a game-item stack passes (gameItemId,
    // materialId = null, quality = null) through the full seven-argument query; a material stack
    // keeps the six-argument overload (gameItemId = null) so the pre-V220 behaviour is unchanged.
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

    // The survivor is the just-written row; fold every other matching (non-offer-backed) row into
    // it. The query locked the whole group FOR UPDATE, so no concurrent writer can double-fold.
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
    // Variante C (REQ-INV-027, R1): merge on physical identity (the key no longer carries the
    // job-order / mission earmark), so union the victims' allocations into the survivor — sum per
    // order / mission id (the per-dimension unique constraint allows one slice per target), and
    // OR-combine the job-order delivered flag (delivered if any folded part was). The survivor's
    // amount already absorbed the victims (total above), so Σ ≤ amount holds; the victims'
    // allocations vanish with them (FK ON DELETE CASCADE) once copied.
    for (InventoryItem victim : victims) {
      InventoryAllocations.unionInto(row, victim);
    }

    inventoryItemRepository.deleteAll(victims);
    // saveAndFlush so the response DTO carries the post-merge amount and the fresh @Version, and
    // the
    // sibling deletes are flushed within this transaction (REQ-FE-003 parity). The survivor is the
    // same managed instance (row), so read the audit fields off it rather than the flush return.
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
   * Whether a row's catalog kind restricts its amounts to whole units: game-item rows always
   * (REQ-INV-029) and material rows measured in {@code PIECE}. Null-safe on both catalog
   * references, so it can be asked of any loaded row.
   *
   * @param item the inventory row.
   * @return {@code true} iff amounts on this row must be whole numbers.
   */
  private static boolean requiresWholeUnits(InventoryItem item) {
    return item.getGameItem() != null
        || (item.getMaterial() != null
            && item.getMaterial().getQuantityType() == QuantityType.PIECE);
  }

  /**
   * User-facing 400 detail for a fractional amount on a whole-unit row, naming the row's actual
   * catalog kind — "PIECE materials" on a game-item row would be misleading because item rows carry
   * no material at all (REQ-INV-029).
   *
   * @param item the whole-unit row the amount was rejected for.
   * @return the catalog-appropriate problem detail.
   */
  private static String wholeUnitAmountDetail(InventoryItem item) {
    return item.getGameItem() != null
        ? "Amount must be a whole number for item stock"
        : "Amount must be a whole number for PIECE materials";
  }

  /**
   * Null-safe catalog display name of a row for audit/finance snapshots: the material name for a
   * material row, the game-item name for a game-item row (REQ-INV-029), an em dash for an orphaned
   * row missing both. Mirrors {@link InventoryAuditLabels#label(InventoryItem)}'s catalog branch
   * without the location suffix.
   *
   * @param item the inventory row (associations lazily loaded but must be within the tx).
   * @return the row's catalog display name.
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
  private static String mergeNotes(Set<String> notes) {
    if (notes.isEmpty()) {
      return null;
    }
    final String joined = String.join(NOTE_MERGE_SEPARATOR, notes);
    return joined.length() > NOTE_MAX_LENGTH ? joined.substring(0, NOTE_MAX_LENGTH) : joined;
  }

  /**
   * Ratchets any active Materialbörse offer on a Lager row down to the row's current stock
   * (REQ-MARKET-013/014) — the public seam the item-facade's update path calls after it edits a
   * row's amount, mirroring the book-out / transfer / rebooking decrement sites that clamp inline.
   * It is a no-op when the stock did not drop below the offered quantity (an increase never changes
   * an offer). Delegates to the kind-aware {@link #ratchetBoardOffersToStock(UUID, double)}.
   *
   * @param itemId the backing Lager row.
   * @param stock the row's current (possibly reduced) stock.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clampOffersToStock(UUID itemId, double stock) {
    ratchetBoardOffersToStock(itemId, stock);
  }

  /**
   * Ratchets down any active Materialbörse offer on a Lager row to the row's reduced stock in the
   * decrementing transaction (REQ-MARKET-013/014) — kind-aware: a {@code MATERIAL} offer clamps its
   * SCU {@code offeredAmount}, a stock-backed {@code ITEM} offer its whole-unit {@code
   * itemQuantity} (the item clamp floors the stock to whole units — item stock is integral). Each
   * atomic conditional update is a no-op for the other kind and for a row backing no offer, so
   * invoking both at every decrement site is correct: a material row can back only a material
   * offer, a game-item row only an item offer. Both are plain {@code @Modifying} updates (no {@code
   * clearAutomatically}), so neither detaches the persistence context — safe to call after the
   * saveAndFlush of the reduced row.
   *
   * @param itemId the backing Lager row whose active offer to clamp.
   * @param stock the row's new (reduced) stock.
   */
  private void ratchetBoardOffersToStock(UUID itemId, double stock) {
    materialExchangeOfferRepository.clampOfferedAmountToStock(itemId, stock);
    materialExchangeOfferRepository.clampItemQuantityToStock(itemId, (int) Math.floor(stock));
  }

  /**
   * Removes every non-personal inventory item from the database — the admin "globales Lager leeren"
   * action. Personal entries ({@code personal = true}) are kept on purpose: they belong to
   * individual users and live outside the squadron's shared stock.
   *
   * <p>Implemented as a single bulk {@code DELETE} via {@link
   * InventoryItemRepository#deleteAllNonPersonal}. The previously load-bearing FK {@code
   * job_order_handover_item.inventory_item_id} was dropped in migration {@code V64} (handover rows
   * snapshot the material data directly), so no pre-cleanup of dependent rows is needed and no
   * {@code @Modifying(clearAutomatically = true)} loop is required — the operation is a one-shot
   * bulk statement that does not collide with any sibling-aggregate {@code @Version}.
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
   * Bulk-checkout: removes all inventory items with the given IDs that belong to the authenticated
   * user. Associations to JobOrders and Missions are cleared on the managed entities inside the
   * loop (no @Modifying bulk-update inside the loop). The actual deleteAllById call happens after
   * the loop, in one batch.
   *
   * @param request the bulk checkout request containing item IDs
   * @param currentUserId the UUID of the authenticated user (JWT sub)
   */
  @Transactional
  public void bulkCheckout(BulkCheckoutRequest request, UUID currentUserId) {
    log.info(
        "Bulk checkout requested by user {} for {} items", currentUserId, request.itemIds().size());

    List<UUID> toDelete = new ArrayList<>();

    for (UUID itemId : request.itemIds()) {
      InventoryItem item =
          inventoryItemRepository
              .findByIdForUpdate(itemId)
              .orElseThrow(() -> new NotFoundException("Inventory item not found: " + itemId));

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

    // Delete all in one batch; each row's job-order / mission allocations cascade away with it
    // (FK ON DELETE CASCADE, V217), so no per-row association clear is needed.
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
    // OPTIMISTIC_FORCE_INCREMENT: delivered now lives on the inverse-side job-order slice, so
    // changing it would not dirty the entry row on its own — force-bump the entry @Version (the
    // single client-echoed token for the whole split) so a stale echo still 409s and the response
    // carries the fresh version for the in-place DOM sync.
    InventoryItem item =
        inventoryItemRepository
            .findByIdForAllocationWrite(id)
            .orElseThrow(() -> new NotFoundException("Inventory item not found"));

    if (!item.getUser().getId().equals(currentUserId) && !isLogistician) {
      throw new AccessDeniedException("You are not allowed to update this inventory item");
    }

    OptimisticLock.check(item.getVersion(), request.version(), InventoryItem.class, id);

    // Variante A (REQ-INV-027): the toggle is (entry, order)-scoped — an entry serving several
    // orders can be delivered for one and still open for another. Flip only the requested order's
    // slice; an absent slice means the order is no longer earmarked on this entry (stale UI) → 404.
    InventoryJobOrderAllocation slice =
        item.getJobOrderAllocations().stream()
            .filter(
                a ->
                    a.getJobOrder() != null && request.jobOrderId().equals(a.getJobOrder().getId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Job-order allocation not found for this inventory item"));
    slice.setDelivered(request.delivered());
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditService.record(
        AuditEventType.INVENTORY_ITEM_DELIVERY_TOGGLED,
        item.getId(),
        InventoryAuditLabels.label(item),
        item.getUser().getId(),
        AuditDetails.of("delivered", request.delivered())
            .with("jobOrder", "#" + slice.getJobOrder().getDisplayId()));
    // OPTIMISTIC_FORCE_INCREMENT bumps the entry @Version at commit, so `saved` still carries the
    // pre-increment value here — hand the client the post-commit version (loaded + 1) so a
    // follow-up
    // toggle of the same row echoes it and does not 409 (REQ-FE-003, REQ-INV-027).
    return inventoryItemMapper
        .toDto(saved)
        .withVersion(InventoryAllocations.forcedNextVersion(saved));
  }
}
