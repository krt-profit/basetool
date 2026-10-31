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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's pool-entry commands: the Einbuchen, the book-out, the sale, the transfer, the note
 * edit, the stolen marking and the allocation writes of the web, checked by the Lager's own rules
 * first so a refusal is answered instead of thrown (REQ-XCH-045).
 */
@Service
@RequiredArgsConstructor
public class InventoryPoolEntryCommands implements PoolEntryCommands {

  /** The tolerance of an amount comparison, which only absorbs floating-point noise. */
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  private final InventoryItemRepository inventoryRepository;
  private final InventoryItemService itemService;
  private final InventoryCheckoutService checkoutService;
  private final InventoryStolenMarkService stolenMarkService;
  private final InventoryPoolChangeService poolChangeService;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final OrgUnitRepository orgUnitRepository;
  private final EarmarkTargets.JobOrders jobOrders;
  private final InventoryEarmarkLookup earmarkLookup;
  private final StockOfferLookup stockOfferLookup;
  private final InventoryProperties inventoryProperties;
  private final InventoryPoolRestorer restorer;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull @Unmodifiable List<PoolRestoreResult> restore(
      @NotNull Map<UUID, ? extends Collection<String>> lots, @NotNull List<PoolRestore> restores) {
    return restorer.restore(lots, restores);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockLots(
      @NotNull Map<UUID, List<StockLot>> holderLots,
      @NotNull Collection<UUID> entryIds,
      @NotNull Collection<PoolRelocation> relocations) {
    Set<Long> keys = new TreeSet<>();
    Set<UUID> rows = new TreeSet<>();
    holderLots.forEach((holder, lots) ->
        lots.forEach(lot -> keys.add(InventoryStockCommands.lotLockKey(holder, lot.key()))));
    Set<UUID> entries = new HashSet<>(entryIds);
    relocations.forEach(relocation -> entries.add(relocation.entryId()));
    List<TargetLot> targets = new ArrayList<>();
    if (!entries.isEmpty()) {
      Map<UUID, InventoryItemRepository.RowLot> byId = new HashMap<>();
      for (InventoryItemRepository.RowLot row : inventoryRepository.findRowLots(entries)) {
        String lot =
            StockLot.key(
                row.getMaterialId(),
                row.getGameItemId(),
                row.getLocationId(),
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen()));
        keys.add(InventoryStockCommands.lotLockKey(row.getHolderId(), lot));
        rows.add(row.getId());
        byId.put(row.getId(), row);
      }
      for (PoolRelocation relocation : relocations) {
        InventoryItemRepository.RowLot row = byId.get(relocation.entryId());
        if (row != null) {
          TargetLot target = TargetLot.of(row, relocation);
          keys.add(InventoryStockCommands.lotLockKey(target.holder(), target.key()));
          targets.add(target);
        }
      }
    }
    InventoryStockCommands.lockInOrder(inventoryRepository, keys);
    holderLots.forEach((holder, lots) -> lots.forEach(lot -> rows.addAll(lotRowIds(holder, lot))));
    targets.forEach(target -> rows.addAll(lotRowIds(target)));
    if (!rows.isEmpty()) {
      inventoryRepository.lockAllByIdOrderById(rows);
    }
  }

  /**
   * Reads the ids of a holder's rows of a lot, personal and shared, without locking them.
   *
   * @param holder the holder
   * @param lot the lot
   * @return the row ids
   */
  private @NotNull List<UUID> lotRowIds(@NotNull UUID holder, @NotNull StockLot lot) {
    return lotRowIds(
        new TargetLot(
            holder,
            lot.material() != null ? lot.material().getId() : null,
            lot.gameItem() != null ? lot.gameItem().getId() : null,
            lot.location().getId(),
            lot.quality(),
            lot.stolen()));
  }

  /**
   * Reads the ids of a holder's rows of a lot named by its ids, personal and shared, without
   * locking them.
   *
   * @param lot the holder and the lot
   * @return the row ids
   */
  private @NotNull List<UUID> lotRowIds(@NotNull TargetLot lot) {
    return lot.materialId() != null
        ? inventoryRepository.findMaterialLotRowIds(
            lot.holder(),
            lot.materialId(),
            lot.locationId(),
            lot.quality() == null ? 0 : lot.quality(),
            lot.stolen())
        : inventoryRepository.findItemLotRowIds(
            lot.holder(), Objects.requireNonNull(lot.gameItemId()), lot.locationId(), lot.stolen());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome bookIn(@NotNull PoolBookIn bookIn, boolean dryRun) {
    StockLot lot = bookIn.lot();
    if (lot.stolen() && !inventoryProperties.stolenMarkingEnabled()) {
      return PoolOutcome.refused(PoolRefusal.STOLEN_MARKING_DISABLED);
    }
    if (!isDirectMember(bookIn.holder(), bookIn.poolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.HOLDER_UNKNOWN);
    }
    PoolRefusal earmarks = checkBookInEarmarks(bookIn);
    if (earmarks != null) {
      return PoolOutcome.refused(earmarks);
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    Map<UUID, PoolEntryState> before = poolRows(bookIn.holder(), lot, bookIn.poolUnitId());
    InventoryItem saved =
        itemService.createInPool(
            new InventoryItemCreateDto(
                bookIn.holder(),
                lot.material() != null ? lot.material().getId() : null,
                lot.gameItem() != null ? lot.gameItem().getId() : null,
                lot.location().getId(),
                lot.material() != null ? (lot.quality() == null ? 0 : lot.quality()) : null,
                bookIn.amount(),
                false,
                lot.stolen(),
                null,
                null,
                bookIn.poolUnitId(),
                bookIn.mergeStock(),
                inputs(bookIn.jobOrders()),
                inputs(bookIn.missions())),
            bookIn.note());
    return PoolOutcome.applied(changes(before, saved.getId()), StockChangeEffects.NONE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome bookOut(@NotNull PoolBookOut bookOut, boolean dryRun) {
    return take(bookOut, dryRun, CheckoutType.DISCARD, null, null);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome sell(@NotNull PoolSale sale, boolean dryRun) {
    return take(sale.take(), dryRun, CheckoutType.SELL, sale.terminal(), sale.proceeds());
  }

  /**
   * Books out or sells an amount of an entry after the Lager's checks.
   *
   * @param take the entry, the amount and the deduct-from plans
   * @param dryRun whether to check it only
   * @param type {@code DISCARD} or {@code SELL}
   * @param terminal a sale's terminal, or {@code null}
   * @param proceeds a sale's proceeds, or {@code null}
   * @return the refusal, or the entry's change and the offers it lowered or removed
   */
  private @NotNull PoolOutcome take(
      @NotNull PoolBookOut take,
      boolean dryRun,
      @NotNull CheckoutType type,
      @Nullable String terminal,
      @Nullable BigDecimal proceeds) {
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(take.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, take.poolUnitId(), take.version());
    if (refusal == null) {
      refusal = checkTake(row, take);
    }
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    PoolEntryState before = stateOf(row);
    if (dryRun) {
      return PoolOutcome.checked();
    }
    StockChangeEffects offers =
        checkoutService.bookOutForPool(
            row.getId(),
            new InventoryItemBookOutDto(
                take.amount(),
                null,
                null,
                type,
                terminal,
                proceeds,
                row.getVersion(),
                null,
                null,
                reductions(take.fromJobOrders()),
                reductions(take.fromMissions())),
            take.actor());
    PoolEntryState after =
        inventoryRepository
            .findById(take.entryId())
            .map(InventoryPoolEntryCommands::stateOf)
            .orElse(null);
    return PoolOutcome.applied(List.of(new PoolEntryChange(before, after)), offers);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome move(@NotNull PoolMove move, boolean dryRun) {
    PoolBookOut take = move.take();
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(take.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, take.poolUnitId(), take.version());
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    UUID holder = row.getUser().getId();
    UUID targetHolder = move.targetHolder() != null ? move.targetHolder() : holder;
    Location targetLocation =
        move.targetLocation() != null ? move.targetLocation() : row.getLocation();
    boolean sameHolder = targetHolder.equals(holder);
    if (sameHolder && !move.targetPoolUnitId().equals(take.poolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    if (sameHolder && targetLocation.getId().equals(row.getLocation().getId())) {
      return PoolOutcome.alreadyDone();
    }
    if (!sameHolder && !isDirectMember(targetHolder, move.targetPoolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.HOLDER_UNKNOWN);
    }
    refusal = checkTake(row, take);
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    Optional<OrgUnit> pool = orgUnitRepository.findById(move.targetPoolUnitId());
    if (pool.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    Map<UUID, PoolEntryState> before =
        poolRows(
            targetHolder,
            new StockLot(
                row.getMaterial(),
                row.getGameItem(),
                targetLocation,
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen())),
            move.targetPoolUnitId());
    before.put(row.getId(), stateOf(row));
    InventoryCheckoutService.PoolTransfer done =
        checkoutService.transferForPool(
            row.getId(),
            new InventoryItemBookOutDto(
                take.amount(),
                sameHolder ? null : targetHolder,
                targetLocation.getId(),
                CheckoutType.TRANSFER,
                null,
                null,
                row.getVersion(),
                null,
                move.mergeStock(),
                reductions(take.fromJobOrders()),
                reductions(take.fromMissions())),
            take.actor(),
            pool.get());
    return PoolOutcome.applied(changes(before, done.targetId()), done.offers());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome movePool(@NotNull PoolChange move, boolean dryRun) {
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(move.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, move.poolUnitId(), move.version());
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    if (move.targetPoolUnitId().equals(move.poolUnitId())) {
      return PoolOutcome.alreadyDone();
    }
    if (!isDirectMember(row.getUser().getId(), move.targetPoolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.HOLDER_UNKNOWN);
    }
    Double part = move.amount();
    if (part != null) {
      if (!unitOf(row).equals(move.unit())) {
        return PoolOutcome.refused(PoolRefusal.UNIT_MISMATCH);
      }
      if (Math.abs(part - held(row)) <= EPSILON) {
        part = null;
      } else {
        refusal = checkSplit(row, part, InventoryPoolChangeService::splitProblem);
        if (refusal != null) {
          return PoolOutcome.refused(refusal);
        }
      }
    }
    if (part == null && InventoryAllocations.sumDonation(row) > EPSILON) {
      return PoolOutcome.refused(PoolRefusal.STOCK_EARMARKED);
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    Map<UUID, PoolEntryState> before =
        poolRows(
            row.getUser().getId(),
            new StockLot(
                row.getMaterial(),
                row.getGameItem(),
                row.getLocation(),
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen())),
            move.targetPoolUnitId());
    before.put(row.getId(), stateOf(row));
    InventoryItem result =
        poolChangeService.movePool(
            row, move.targetPoolUnitId(), part, move.mergeStock(), move.actor());
    return PoolOutcome.applied(changes(before, result.getId()), StockChangeEffects.NONE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome setNote(@NotNull PoolNote note, boolean dryRun) {
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(note.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, note.poolUnitId(), note.version());
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    if (Objects.equals(
        StringNormalization.trimToNull(note.note()),
        StringNormalization.trimToNull(row.getNote()))) {
      return PoolOutcome.alreadyDone();
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    PoolEntryState before = stateOf(row);
    itemService.updateNote(
        row.getId(),
        new InventoryItemNoteUpdateRequest(note.note(), row.getVersion()),
        note.actor(),
        true);
    return PoolOutcome.applied(
        List.of(new PoolEntryChange(before, stateOf(row))), StockChangeEffects.NONE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome markStolen(@NotNull PoolStolen stolen, boolean dryRun) {
    if (!inventoryProperties.stolenMarkingEnabled()) {
      return PoolOutcome.refused(PoolRefusal.STOLEN_MARKING_DISABLED);
    }
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(stolen.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, stolen.poolUnitId(), stolen.version());
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    if (Boolean.TRUE.equals(row.getStolen()) == stolen.stolen()) {
      return PoolOutcome.alreadyDone();
    }
    Double part = stolen.amount();
    if (part != null) {
      if (!unitOf(row).equals(stolen.unit())) {
        return PoolOutcome.refused(PoolRefusal.UNIT_MISMATCH);
      }
      if (Math.abs(part - held(row)) <= EPSILON) {
        part = null;
      } else {
        refusal = checkSplit(row, part, InventoryStolenMarkService::splitProblem);
        if (refusal != null) {
          return PoolOutcome.refused(refusal);
        }
      }
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    Map<UUID, PoolEntryState> before =
        poolRows(
            row.getUser().getId(),
            new StockLot(
                row.getMaterial(),
                row.getGameItem(),
                row.getLocation(),
                row.getQuality(),
                stolen.stolen()),
            stolen.poolUnitId());
    before.put(row.getId(), stateOf(row));
    UUID result =
        stolenMarkService
            .mark(
                row.getId(),
                new InventoryItemStolenMarkDto(row.getVersion(), stolen.stolen(), part),
                stolen.actor(),
                true)
            .id();
    return PoolOutcome.applied(changes(before, result), StockChangeEffects.NONE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome allocate(@NotNull PoolAllocation allocation, boolean dryRun) {
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(allocation.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    PoolRefusal refusal = checkEntry(row, allocation.poolUnitId(), allocation.version());
    if (refusal != null) {
      return PoolOutcome.refused(refusal);
    }
    boolean jobOrder = allocation.dimension() == EarmarkDimension.JOB_ORDER;
    boolean present = hasSlice(row, allocation.targetId(), jobOrder);
    double current = sliceAmount(row, allocation.targetId(), jobOrder);
    if (allocation.change() == EarmarkChange.REMOVE) {
      if (!present) {
        return PoolOutcome.alreadyDone();
      }
    } else {
      if (allocation.change() == EarmarkChange.CHANGE
          && present
          && unitOf(row).equals(allocation.unit())
          && Math.abs(Objects.requireNonNull(allocation.amount()) - current) <= EPSILON) {
        return PoolOutcome.alreadyDone();
      }
      refusal = checkAllocation(row, allocation, present, current);
      if (refusal != null) {
        return PoolOutcome.refused(refusal);
      }
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    PoolEntryState before = stateOf(row);
    InventoryAllocationWriteDto write =
        new InventoryAllocationWriteDto(
            jobOrder
                ? InventoryAllocationDimension.JOB_ORDER
                : InventoryAllocationDimension.MISSION,
            allocation.targetId(),
            allocation.amount(),
            row.getVersion());
    itemService.writeAllocationForPool(row, write, allocation.change());
    return PoolOutcome.applied(
        List.of(new PoolEntryChange(before, stateOf(row))), StockChangeEffects.NONE);
  }

  /**
   * Checks an earmark that is added or changed as the Lager's allocation writes do: the entry's
   * unit, a job order that needs the material or item or an existing mission, no mission on item
   * stock, no second earmark to the same target, an existing earmark to change, and no more
   * earmarked per dimension than the entry holds.
   *
   * @param row the locked entry
   * @param allocation the earmark write
   * @param present whether the entry has an earmark to the target
   * @param current the amount of that earmark, {@code 0} when it has none
   * @return the refusal, or {@code null} when the earmark may be written
   */
  private @Nullable PoolRefusal checkAllocation(
      @NotNull InventoryItem row,
      @NotNull PoolAllocation allocation,
      boolean present,
      double current) {
    if (!unitOf(row).equals(allocation.unit())) {
      return PoolRefusal.UNIT_MISMATCH;
    }
    boolean jobOrder = allocation.dimension() == EarmarkDimension.JOB_ORDER;
    if (!jobOrder && row.getGameItem() != null) {
      return PoolRefusal.EARMARK_TARGET_INVALID;
    }
    if (allocation.change() == EarmarkChange.ADD) {
      if (present || !targetExists(row, allocation.targetId(), jobOrder)) {
        return PoolRefusal.EARMARK_TARGET_INVALID;
      }
    } else if (!present) {
      return PoolRefusal.EARMARK_TARGET_INVALID;
    }
    double others =
        (jobOrder ? InventoryAllocations.sumJobOrder(row) : InventoryAllocations.sumMission(row))
            - current;
    double proposed =
        ScuAmounts.roundToScuScale(others + Objects.requireNonNull(allocation.amount()));
    if (proposed > held(row) + EPSILON) {
      return PoolRefusal.OVER_ALLOCATION;
    }
    return null;
  }

  /**
   * Whether an entry has an earmark slice to one target, whatever its amount.
   *
   * @param row the entry
   * @param targetId the job order or mission
   * @param jobOrder {@code true} for a job order, {@code false} for a mission
   * @return {@code true} when the entry holds such a slice
   */
  private static boolean hasSlice(
      @NotNull InventoryItem row, @NotNull UUID targetId, boolean jobOrder) {
    return jobOrder
        ? InventoryAllocations.jobOrderSlice(row, targetId) != null
        : InventoryAllocations.missionSlice(row, targetId) != null;
  }

  /**
   * Whether an earmark's target exists and, for a job order, needs the entry's material or item.
   *
   * @param row the entry
   * @param targetId the job order or mission
   * @param jobOrder {@code true} for a job order, {@code false} for a mission
   * @return {@code true} when the entry may be earmarked to it
   */
  private boolean targetExists(
      @NotNull InventoryItem row, @NotNull UUID targetId, boolean jobOrder) {
    if (!jobOrder) {
      return earmarkLookup.mission(targetId) != null;
    }
    Set<UUID> needed =
        row.getMaterial() != null
            ? jobOrders.requiredMaterialIds(targetId)
            : jobOrders.requiredGameItemIds(targetId);
    UUID catalogueId =
        row.getMaterial() != null
            ? row.getMaterial().getId()
            : Objects.requireNonNull(row.getGameItem()).getId();
    return needed != null && needed.contains(catalogueId);
  }

  /**
   * Checks that an entry is a shared entry of the named pool at the version the writer read.
   *
   * @param row the locked entry
   * @param poolUnitId the pool the writer names
   * @param version the version the writer read
   * @return the refusal, or {@code null} when the entry may be written
   */
  private static @Nullable PoolRefusal checkEntry(
      @NotNull InventoryItem row, @NotNull UUID poolUnitId, long version) {
    if (Boolean.TRUE.equals(row.getPersonal())
        || row.getOwningOrgUnit() == null
        || !row.getOwningOrgUnit().getId().equals(poolUnitId)) {
      return PoolRefusal.NOT_PERMITTED;
    }
    if (row.getVersion() == null || row.getVersion() != version) {
      return PoolRefusal.VERSION_CONFLICT;
    }
    return null;
  }

  /**
   * Checks an amount taken out of an entry as the Lager's book-out does: the entry's unit, its
   * amount, its earmarks and donation pledges, and the deduct-from plans.
   *
   * @param row the locked entry
   * @param take the amount and the plans
   * @return the refusal, or {@code null} when the amount may be taken
   */
  private static @Nullable PoolRefusal checkTake(
      @NotNull InventoryItem row, @NotNull PoolBookOut take) {
    if (!unitOf(row).equals(take.unit())) {
      return PoolRefusal.UNIT_MISMATCH;
    }
    double amount = take.amount();
    InventoryStockDecisions.BookOutRefusal bookOut =
        InventoryStockDecisions.bookOut(row, amount, !take.fromMissions().isEmpty());
    if (bookOut != null) {
      return switch (bookOut) {
        case EXCEEDS_STOCK -> PoolRefusal.INSUFFICIENT_STOCK;
        case MISSION_EARMARK_ON_ITEM_STOCK -> PoolRefusal.EARMARK_TARGET_INVALID;
        case FRACTIONAL_AMOUNT -> PoolRefusal.UNIT_MISMATCH;
      };
    }
    double held = held(row);
    PoolRefusal plan = checkPlan(row, take.fromJobOrders(), amount, true);
    if (plan == null) {
      plan = checkPlan(row, take.fromMissions(), amount, false);
    }
    if (plan != null) {
      return plan;
    }
    if (amount > held - InventoryAllocations.sumDonation(row) + EPSILON) {
      return PoolRefusal.STOCK_EARMARKED;
    }
    return null;
  }

  /**
   * Checks a part split off an entry by the Lager's split decision; a fraction of piece or item
   * stock is {@code UNIT_MISMATCH}, and a part that is not positive is thrown as the web's problem.
   *
   * @param row the locked entry
   * @param part the part split off, not the whole entry
   * @param problem the web's problem for a refusal the commands do not answer
   * @return the refusal, or {@code null} when the split may happen
   */
  private @Nullable PoolRefusal checkSplit(
      @NotNull InventoryItem row,
      double part,
      @NotNull Function<InventoryStockDecisions.SplitRefusal, RuntimeException> problem) {
    InventoryStockDecisions.SplitRefusal refusal =
        InventoryStockDecisions.split(row, part, stockOfferLookup.activeOfferedAmount(row.getId()));
    if (refusal == null) {
      return null;
    }
    return switch (refusal) {
      case EXCEEDS_STOCK -> PoolRefusal.INSUFFICIENT_STOCK;
      case FRACTIONAL_AMOUNT -> PoolRefusal.UNIT_MISMATCH;
      case BELOW_OFFER -> PoolRefusal.STOCK_OFFERED;
      case EARMARKS_EXCEED_REST -> PoolRefusal.STOCK_EARMARKED;
      case AMOUNT_NOT_POSITIVE -> throw problem.apply(refusal);
    };
  }

  /**
   * Checks a book-in's earmarks as the Lager's Einbuchen does: each target once, a job order that
   * needs the material or item, an existing mission, no mission on item stock, and per dimension no
   * more than the amount.
   *
   * @param bookIn the book-in
   * @return the refusal, or {@code null} when the earmarks are valid
   */
  private @Nullable PoolRefusal checkBookInEarmarks(@NotNull PoolBookIn bookIn) {
    StockLot lot = bookIn.lot();
    if (lot.gameItem() != null && !bookIn.missions().isEmpty()) {
      return PoolRefusal.EARMARK_TARGET_INVALID;
    }
    if (sum(bookIn.jobOrders()) > bookIn.amount() + EPSILON
        || sum(bookIn.missions()) > bookIn.amount() + EPSILON) {
      return PoolRefusal.OVER_ALLOCATION;
    }
    if (!distinct(bookIn.jobOrders()) || !distinct(bookIn.missions())) {
      return PoolRefusal.EARMARK_TARGET_INVALID;
    }
    for (PoolEarmark earmark : bookIn.jobOrders()) {
      Set<UUID> needed =
          lot.material() != null
              ? jobOrders.requiredMaterialIds(earmark.targetId())
              : jobOrders.requiredGameItemIds(earmark.targetId());
      UUID catalogueId =
          lot.material() != null
              ? lot.material().getId()
              : Objects.requireNonNull(lot.gameItem()).getId();
      if (needed == null || !needed.contains(catalogueId)) {
        return PoolRefusal.EARMARK_TARGET_INVALID;
      }
    }
    for (PoolEarmark earmark : bookIn.missions()) {
      if (earmarkLookup.mission(earmark.targetId()) == null) {
        return PoolRefusal.EARMARK_TARGET_INVALID;
      }
    }
    return null;
  }

  /**
   * Checks one dimension's deduct-from plan of a book-out as the Lager's plan resolution does,
   * without its default plan: what the plan does not cover must come from the dimension's free
   * part.
   *
   * @param row the locked entry
   * @param plan the amounts to take from each earmark
   * @param amount the amount booked out
   * @param jobOrderDimension {@code true} for the job orders, {@code false} for the missions
   * @return the refusal, or {@code null} when the plan holds
   */
  private static @Nullable PoolRefusal checkPlan(
      @NotNull InventoryItem row,
      @NotNull List<PoolEarmark> plan,
      double amount,
      boolean jobOrderDimension) {
    double earmarked =
        jobOrderDimension
            ? InventoryAllocations.sumJobOrder(row)
            : InventoryAllocations.sumMission(row);
    double free = ScuAmounts.roundToScuScale(held(row) - earmarked);
    Set<UUID> seen = new HashSet<>();
    double taken = 0.0;
    for (PoolEarmark line : plan) {
      double slice = sliceAmount(row, line.targetId(), jobOrderDimension);
      if (slice <= 0.0 || !seen.add(line.targetId()) || line.amount() > slice + EPSILON) {
        return PoolRefusal.EARMARK_TARGET_INVALID;
      }
      taken += line.amount();
    }
    if (taken > amount + EPSILON) {
      return PoolRefusal.EARMARK_TARGET_INVALID;
    }
    if (amount - taken > free + EPSILON) {
      return PoolRefusal.STOCK_EARMARKED;
    }
    return null;
  }

  /**
   * Returns the amount of an entry's earmark to one target.
   *
   * @param row the entry
   * @param targetId the job order or mission
   * @param jobOrderDimension {@code true} for a job order, {@code false} for a mission
   * @return the amount, {@code 0} when the entry is not earmarked to it
   */
  private static double sliceAmount(
      @NotNull InventoryItem row, @NotNull UUID targetId, boolean jobOrderDimension) {
    if (jobOrderDimension) {
      InventoryJobOrderAllocation slice = InventoryAllocations.jobOrderSlice(row, targetId);
      return slice == null || slice.getAmount() == null ? 0.0 : slice.getAmount();
    }
    InventoryMissionAllocation slice = InventoryAllocations.missionSlice(row, targetId);
    return slice == null || slice.getAmount() == null ? 0.0 : slice.getAmount();
  }

  /**
   * Whether a member is a direct member of an org unit.
   *
   * @param member the member
   * @param orgUnitId the org unit
   * @return {@code true} for a direct membership
   */
  private boolean isDirectMember(@NotNull UUID member, @NotNull UUID orgUnitId) {
    return membershipQueryService.findDirectMembershipOrgUnitIds(member).contains(orgUnitId);
  }

  /**
   * Captures a holder's shared rows of a lot in one pool, for the snapshot of a write that may
   * merge into them.
   *
   * @param holder the holder
   * @param lot the lot
   * @param poolUnitId the pool
   * @return the rows' states by id
   */
  private @NotNull Map<UUID, PoolEntryState> poolRows(
      @NotNull UUID holder, @NotNull StockLot lot, @NotNull UUID poolUnitId) {
    Map<UUID, PoolEntryState> rows = new TreeMap<>();
    for (InventoryItem row : holderRows(holder, lot)) {
      if (!Boolean.TRUE.equals(row.getPersonal())
          && row.getOwningOrgUnit() != null
          && row.getOwningOrgUnit().getId().equals(poolUnitId)) {
        rows.put(row.getId(), stateOf(row));
      }
    }
    return rows;
  }

  /**
   * Compares the entries captured before a write with their state after it.
   *
   * @param before the captured entries by id
   * @param result the entry that holds the written stock afterwards
   * @return every entry the write created, changed or removed
   */
  private @NotNull List<PoolEntryChange> changes(
      @NotNull Map<UUID, PoolEntryState> before, @NotNull UUID result) {
    inventoryRepository.flush();
    List<PoolEntryChange> changes = new ArrayList<>();
    before.forEach((id, state) -> {
      PoolEntryState after =
          inventoryRepository.findById(id).map(InventoryPoolEntryCommands::stateOf).orElse(null);
      if (!state.equals(after)) {
        changes.add(new PoolEntryChange(state, after));
      }
    });
    if (!before.containsKey(result)) {
      inventoryRepository
          .findById(result)
          .ifPresent(row -> changes.add(new PoolEntryChange(null, stateOf(row))));
    }
    return changes;
  }

  /**
   * Locks a holder's rows of a lot, personal and shared, for the snapshot of a write.
   *
   * @param holder the holder
   * @param lot the lot
   * @return the rows
   */
  private @NotNull List<InventoryItem> holderRows(@NotNull UUID holder, @NotNull StockLot lot) {
    return lot.material() != null
        ? inventoryRepository.lockMaterialLot(
            holder,
            lot.material().getId(),
            lot.location().getId(),
            lot.quality() == null ? 0 : lot.quality(),
            lot.stolen())
        : inventoryRepository.lockItemLot(
            holder,
            Objects.requireNonNull(lot.gameItem()).getId(),
            lot.location().getId(),
            lot.stolen());
  }

  /**
   * Captures an entry for the journal.
   *
   * @param row the entry, a shared row of a pool
   * @return its state
   */
  static @NotNull PoolEntryState stateOf(@NotNull InventoryItem row) {
    return new PoolEntryState(
        row.getId(),
        row.getUser().getId(),
        Objects.requireNonNull(row.getOwningOrgUnit()).getId(),
        StockLot.key(
            row.getMaterial() != null ? row.getMaterial().getId() : null,
            row.getGameItem() != null ? row.getGameItem().getId() : null,
            row.getLocation().getId(),
            row.getQuality(),
            Boolean.TRUE.equals(row.getStolen())),
        held(row),
        row.getVersion() == null ? 0L : row.getVersion(),
        InventoryAllocations.jobOrderEarmarks(row),
        InventoryAllocations.missionEarmarks(row));
  }

  /**
   * Returns an entry's amount.
   *
   * @param row the entry
   * @return its amount, {@code 0} when it has none
   */
  static double held(@NotNull InventoryItem row) {
    return row.getAmount() == null ? 0.0 : row.getAmount();
  }

  /**
   * Returns the unit an entry's amounts are counted in.
   *
   * @param row the entry
   * @return {@code PIECE} for items and piece-counted materials, {@code SCU} otherwise
   */
  private static @NotNull String unitOf(@NotNull InventoryItem row) {
    return (row.countsWholeUnits() ? QuantityType.PIECE : QuantityType.SCU).name();
  }

  /**
   * Sums the amounts of earmarks.
   *
   * @param earmarks the earmarks
   * @return the sum
   */
  private static double sum(@NotNull List<PoolEarmark> earmarks) {
    return earmarks.stream().mapToDouble(PoolEarmark::amount).sum();
  }

  /**
   * Whether every earmark names its own target.
   *
   * @param earmarks the earmarks
   * @return {@code true} when no target appears twice
   */
  private static boolean distinct(@NotNull List<PoolEarmark> earmarks) {
    return earmarks.stream().map(PoolEarmark::targetId).distinct().count() == earmarks.size();
  }

  /**
   * Maps earmarks to the Einbuchen's allocation inputs.
   *
   * @param earmarks the earmarks
   * @return the inputs
   */
  private static @NotNull List<InventoryAllocationInput> inputs(
      @NotNull List<PoolEarmark> earmarks) {
    return earmarks.stream()
        .map(earmark -> new InventoryAllocationInput(earmark.targetId(), earmark.amount()))
        .toList();
  }

  /**
   * Maps a deduct-from plan to the book-out's reductions; an empty list keeps the Lager from
   * choosing the earmarks itself.
   *
   * @param plan the plan
   * @return the reductions
   */
  private static @NotNull List<AllocationReductionDto> reductions(@NotNull List<PoolEarmark> plan) {
    return plan.stream()
        .map(line -> new AllocationReductionDto(line.targetId(), line.amount()))
        .toList();
  }

  /**
   * A holder's lot named by its ids.
   *
   * @param holder the holder
   * @param materialId the material, or {@code null} for an item
   * @param gameItemId the item, or {@code null} for a material
   * @param locationId the location
   * @param quality the quality, or {@code null}
   * @param stolen the stolen marker
   */
  private record TargetLot(
      @NotNull UUID holder,
      @Nullable UUID materialId,
      @Nullable UUID gameItemId,
      @NotNull UUID locationId,
      @Nullable Integer quality,
      boolean stolen) {

    /**
     * The lot an entry is relocated into.
     *
     * @param row the entry's lot
     * @param relocation what changes
     * @return the target lot
     */
    static @NotNull TargetLot of(
        @NotNull InventoryItemRepository.RowLot row, @NotNull PoolRelocation relocation) {
      return new TargetLot(
          relocation.holder() != null ? relocation.holder() : row.getHolderId(),
          row.getMaterialId(),
          row.getGameItemId(),
          relocation.locationId() != null ? relocation.locationId() : row.getLocationId(),
          row.getQuality(),
          relocation.stolen() != null ? relocation.stolen() : Boolean.TRUE.equals(row.getStolen()));
    }

    /**
     * The stock lot key ({@link StockLot#key}) whose advisory lock guards this holder's lot.
     *
     * @return the lot key, without the holder
     */
    @NotNull
    String key() {
      return StockLot.key(materialId, gameItemId, locationId, quality, stolen);
    }
  }
}
