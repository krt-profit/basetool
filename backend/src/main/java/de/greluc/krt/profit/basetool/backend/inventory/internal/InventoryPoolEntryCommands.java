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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's pool-entry commands: the Einbuchen and the book-out of the web, checked by the
 * Lager's own rules first so a refusal is answered instead of thrown (REQ-XCH-045).
 */
@Service
@RequiredArgsConstructor
public class InventoryPoolEntryCommands implements PoolEntryCommands {

  /** The tolerance of an amount comparison, which only absorbs floating-point noise. */
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  private final InventoryItemRepository inventoryRepository;
  private final InventoryItemService itemService;
  private final InventoryCheckoutService checkoutService;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final EarmarkTargets.JobOrders jobOrders;
  private final InventoryEarmarkLookup earmarkLookup;
  private final InventoryProperties inventoryProperties;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockLots(
      @NotNull Map<UUID, Set<String>> holderLots, @NotNull Collection<UUID> entryIds) {
    Set<Long> keys = new TreeSet<>();
    holderLots.forEach(
        (holder, lots) ->
            lots.forEach(lot -> keys.add(InventoryStockCommands.lotLockKey(holder, lot))));
    if (!entryIds.isEmpty()) {
      for (InventoryItemRepository.RowLot row : inventoryRepository.findRowLots(entryIds)) {
        String lot =
            StockLot.key(
                row.getMaterialId(),
                row.getGameItemId(),
                row.getLocationId(),
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen()));
        keys.add(InventoryStockCommands.lotLockKey(row.getHolderId(), lot));
      }
    }
    keys.forEach(inventoryRepository::lockExchangeLot);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome bookIn(@NotNull PoolBookIn bookIn, boolean dryRun) {
    StockLot lot = bookIn.lot();
    if (lot.stolen() && !inventoryProperties.stolenMarkingEnabled()) {
      return PoolOutcome.refused(PoolRefusal.STOLEN_MARKING_DISABLED);
    }
    if (!membershipQueryService
        .findDirectMembershipOrgUnitIds(bookIn.holder())
        .contains(bookIn.poolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.HOLDER_UNKNOWN);
    }
    PoolRefusal earmarks = checkBookInEarmarks(bookIn);
    if (earmarks != null) {
      return PoolOutcome.refused(earmarks);
    }
    if (dryRun) {
      return PoolOutcome.checked();
    }
    Map<UUID, PoolEntryState> before = new TreeMap<>();
    for (InventoryItem row : holderRows(bookIn.holder(), lot)) {
      if (!Boolean.TRUE.equals(row.getPersonal())
          && row.getOwningOrgUnit() != null
          && row.getOwningOrgUnit().getId().equals(bookIn.poolUnitId())) {
        before.put(row.getId(), stateOf(row));
      }
    }
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
    List<PoolEntryChange> changes = new ArrayList<>();
    changes.add(new PoolEntryChange(before.get(saved.getId()), stateOf(saved)));
    before.forEach(
        (id, state) -> {
          if (!id.equals(saved.getId()) && inventoryRepository.findById(id).isEmpty()) {
            changes.add(new PoolEntryChange(state, null));
          }
        });
    return new PoolOutcome(null, List.copyOf(changes), StockChangeEffects.NONE);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PoolOutcome bookOut(@NotNull PoolBookOut bookOut, boolean dryRun) {
    Optional<InventoryItem> locked = inventoryRepository.findByIdForUpdate(bookOut.entryId());
    if (locked.isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    InventoryItem row = locked.get();
    if (Boolean.TRUE.equals(row.getPersonal())
        || row.getOwningOrgUnit() == null
        || !row.getOwningOrgUnit().getId().equals(bookOut.poolUnitId())) {
      return PoolOutcome.refused(PoolRefusal.NOT_PERMITTED);
    }
    if (row.getVersion() == null || row.getVersion() != bookOut.version()) {
      return PoolOutcome.refused(PoolRefusal.VERSION_CONFLICT);
    }
    boolean pieces =
        row.getGameItem() != null
            || (row.getMaterial() != null
                && row.getMaterial().getQuantityType() == QuantityType.PIECE);
    if (!(pieces ? QuantityType.PIECE : QuantityType.SCU).name().equals(bookOut.unit())) {
      return PoolOutcome.refused(PoolRefusal.UNIT_MISMATCH);
    }
    double amount = bookOut.amount();
    double held = row.getAmount() == null ? 0.0 : row.getAmount();
    if (amount > held + EPSILON) {
      return PoolOutcome.refused(PoolRefusal.INSUFFICIENT_STOCK);
    }
    if (row.getGameItem() != null && !bookOut.fromMissions().isEmpty()) {
      return PoolOutcome.refused(PoolRefusal.EARMARK_TARGET_INVALID);
    }
    PoolRefusal plan = checkPlan(row, bookOut.fromJobOrders(), amount, true);
    if (plan == null) {
      plan = checkPlan(row, bookOut.fromMissions(), amount, false);
    }
    if (plan != null) {
      return PoolOutcome.refused(plan);
    }
    if (amount > held - InventoryAllocations.sumDonation(row) + EPSILON) {
      return PoolOutcome.refused(PoolRefusal.STOCK_EARMARKED);
    }
    PoolEntryState before = stateOf(row);
    if (dryRun) {
      return PoolOutcome.checked();
    }
    StockChangeEffects offers =
        checkoutService.bookOutForPool(
            row.getId(),
            new InventoryItemBookOutDto(
                amount,
                null,
                null,
                CheckoutType.DISCARD,
                null,
                null,
                row.getVersion(),
                null,
                null,
                reductions(bookOut.fromJobOrders()),
                reductions(bookOut.fromMissions())),
            bookOut.actor());
    PoolEntryState after =
        inventoryRepository.findById(bookOut.entryId()).map(this::stateOf).orElse(null);
    return new PoolOutcome(null, List.of(new PoolEntryChange(before, after)), offers);
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
    double held = row.getAmount() == null ? 0.0 : row.getAmount();
    double earmarked =
        jobOrderDimension
            ? InventoryAllocations.sumJobOrder(row)
            : InventoryAllocations.sumMission(row);
    double free = ScuAmounts.roundToScuScale(held - earmarked);
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
   * Locks a holder's rows of a lot, personal and shared, for the snapshot of a book-in.
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
  private @NotNull PoolEntryState stateOf(@NotNull InventoryItem row) {
    Map<UUID, Double> jobs = new TreeMap<>();
    for (InventoryJobOrderAllocation slice : row.getJobOrderAllocations()) {
      if (slice.getJobOrderId() != null && slice.getAmount() != null) {
        jobs.merge(slice.getJobOrderId(), slice.getAmount(), Double::sum);
      }
    }
    Map<UUID, Double> missions = new TreeMap<>();
    for (InventoryMissionAllocation slice : row.getMissionAllocations()) {
      if (slice.getMissionId() != null && slice.getAmount() != null) {
        missions.merge(slice.getMissionId(), slice.getAmount(), Double::sum);
      }
    }
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
        row.getAmount() == null ? 0.0 : row.getAmount(),
        row.getVersion() == null ? 0L : row.getVersion(),
        jobs,
        missions);
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
}
