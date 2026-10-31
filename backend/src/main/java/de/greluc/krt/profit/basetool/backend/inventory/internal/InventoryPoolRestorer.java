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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryState;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestore;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets shared Lager entries back to the states a connected application's undo names (REQ-XCH-046):
 * it removes, recreates or rewrites each entry after checking that it is still as the client's
 * writes left it, and audits every change as {@code INVENTORY_ITEM_RESTORED}.
 */
@Service
@RequiredArgsConstructor
class InventoryPoolRestorer {

  /** The tolerance of an amount comparison, which only absorbs floating-point noise. */
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  /** The separator of a stock lot key's parts. */
  private static final Pattern LOT_KEY_SEPARATOR = Pattern.compile("\\|");

  private final InventoryItemRepository inventoryRepository;
  private final UserRepository userRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final LocationRepository locationRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final InventoryEarmarkLookup earmarkLookup;
  private final StockChangeObserver stockChangeObserver;
  private final AuditRecorder auditRecorder;

  /**
   * Locks every lot and entry the restores touch together with the caller's further lots, then
   * restores each entry in the given order.
   *
   * @param lots further lots the caller writes in the same transaction, as lot keys by holder
   * @param restores the entries to set back
   * @return the result of each restore, in the given order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  @Unmodifiable
  List<PoolRestoreResult> restore(
      @NotNull Map<UUID, ? extends Collection<String>> lots, @NotNull List<PoolRestore> restores) {
    lock(lots, restores);
    if (restores.isEmpty()) {
      return List.of();
    }
    List<PoolRestoreResult> results = new ArrayList<>(restores.size());
    for (PoolRestore restore : restores) {
      results.add(restoreOne(restore));
    }
    inventoryRepository.flush();
    return List.copyOf(results);
  }

  /**
   * Takes the advisory locks of the caller's further lots, of every lot an entry lies in now and of
   * every lot it goes back to, in one ascending pass, and then the rows of the entries in ascending
   * id order (ADR-0229).
   *
   * @param lots further lots the caller writes in the same transaction, as lot keys by holder
   * @param restores the entries to set back
   */
  private void lock(
      @NotNull Map<UUID, ? extends Collection<String>> lots, @NotNull List<PoolRestore> restores) {
    Set<Long> keys = new TreeSet<>();
    lots.forEach((holder, lotKeys) ->
        lotKeys.forEach(lotKey -> keys.add(InventoryStockCommands.lotLockKey(holder, lotKey))));
    Set<UUID> ids = new TreeSet<>();
    restores.forEach(restore -> ids.add(restore.entryId()));
    Set<UUID> present = new TreeSet<>();
    List<InventoryItemRepository.RowLot> rows =
        ids.isEmpty() ? List.of() : inventoryRepository.findRowLots(ids);
    for (InventoryItemRepository.RowLot row : rows) {
      keys.add(
          InventoryStockCommands.lotLockKey(
              row.getHolderId(),
              StockLot.key(
                  row.getMaterialId(),
                  row.getGameItemId(),
                  row.getLocationId(),
                  row.getQuality(),
                  Boolean.TRUE.equals(row.getStolen()))));
      present.add(row.getId());
    }
    for (PoolRestore restore : restores) {
      PoolEntryState target = restore.target();
      if (target != null) {
        keys.add(InventoryStockCommands.lotLockKey(target.holder(), target.lotKey()));
      }
    }
    InventoryStockCommands.lockInOrder(inventoryRepository, keys);
    if (!present.isEmpty()) {
      inventoryRepository.lockAllByIdOrderById(present);
    }
  }

  /**
   * Restores one entry after checking that it is still as the client's writes left it.
   *
   * @param restore the entry, the state the writes left it in and the state to restore
   * @return the result
   */
  private @NotNull PoolRestoreResult restoreOne(@NotNull PoolRestore restore) {
    Optional<InventoryItem> found = inventoryRepository.findById(restore.entryId());
    PoolEntryState expected = restore.expected();
    if (expected == null ? found.isPresent() : !isAsLeft(found.orElse(null), expected)) {
      return PoolRestoreResult.CHANGED_AFTERWARDS;
    }
    PoolEntryState target = restore.target();
    if (target == null) {
      if (found.isPresent() && InventoryAllocations.sumDonation(found.get()) > EPSILON) {
        return PoolRestoreResult.CHANGED_AFTERWARDS;
      }
      found.ifPresent(this::remove);
      return PoolRestoreResult.RESTORED;
    }
    Resolved resolved = resolve(target);
    if (resolved == null) {
      return PoolRestoreResult.GONE;
    }
    if (found.isEmpty()) {
      recreate(target, resolved);
      return PoolRestoreResult.RESTORED;
    }
    InventoryItem row = found.get();
    if (InventoryAllocations.sumDonation(row) > target.amount() + EPSILON) {
      return PoolRestoreResult.CHANGED_AFTERWARDS;
    }
    rewrite(row, target, resolved);
    return PoolRestoreResult.RESTORED;
  }

  /**
   * Whether an entry is a shared entry exactly in the state the client's writes left it in.
   *
   * @param row the entry, or {@code null} when it is gone
   * @param expected the state the writes left it in
   * @return {@code true} when nothing changed it since
   */
  private static boolean isAsLeft(@Nullable InventoryItem row, @NotNull PoolEntryState expected) {
    return row != null
        && !Boolean.TRUE.equals(row.getPersonal())
        && row.getOwningOrgUnit() != null
        && sameState(InventoryPoolEntryCommands.stateOf(row), expected);
  }

  /**
   * Compares two journaled states field by field, the amounts within the tolerance.
   *
   * @param left one state
   * @param right the other state
   * @return {@code true} when they agree
   */
  private static boolean sameState(@NotNull PoolEntryState left, @NotNull PoolEntryState right) {
    return left.id().equals(right.id())
        && left.version() == right.version()
        && sameContent(left, right);
  }

  /**
   * Compares the holder, pool, lot, amount and earmarks of two states, ignoring id and version.
   *
   * @param left one state
   * @param right the other state
   * @return {@code true} when they agree
   */
  private static boolean sameContent(@NotNull PoolEntryState left, @NotNull PoolEntryState right) {
    return left.holder().equals(right.holder())
        && left.poolUnitId().equals(right.poolUnitId())
        && left.lotKey().equals(right.lotKey())
        && Math.abs(left.amount() - right.amount()) <= EPSILON
        && sameAmounts(left.jobOrders(), right.jobOrders())
        && sameAmounts(left.missions(), right.missions());
  }

  /**
   * Compares two earmark maps, the amounts within the tolerance.
   *
   * @param left one map
   * @param right the other map
   * @return {@code true} when they name the same targets with the same amounts
   */
  private static boolean sameAmounts(
      @NotNull Map<UUID, Double> left, @NotNull Map<UUID, Double> right) {
    if (!left.keySet().equals(right.keySet())) {
      return false;
    }
    for (Map.Entry<UUID, Double> entry : left.entrySet()) {
      Double other = right.get(entry.getKey());
      if (entry.getValue() == null
          || other == null
          || Math.abs(entry.getValue() - other) > EPSILON) {
        return false;
      }
    }
    return true;
  }

  /**
   * Resolves what a state names, refusing one that names something gone or a holder who no longer
   * belongs to the pool.
   *
   * @param target the state to restore
   * @return the resolved references, or {@code null} when something is gone
   */
  private @Nullable Resolved resolve(@NotNull PoolEntryState target) {
    ParsedLot lot = parse(target.lotKey());
    if (lot == null) {
      return null;
    }
    Material material =
        lot.materialId() == null
            ? null
            : materialRepository.findById(lot.materialId()).orElse(null);
    GameItem gameItem =
        lot.gameItemId() == null
            ? null
            : gameItemRepository.findById(lot.gameItemId()).orElse(null);
    Location location = locationRepository.findById(lot.locationId()).orElse(null);
    User holder = userRepository.findById(target.holder()).orElse(null);
    OrgUnit pool = orgUnitRepository.findById(target.poolUnitId()).orElse(null);
    if ((material == null && gameItem == null)
        || location == null
        || holder == null
        || pool == null
        || !membershipQueryService
            .findDirectMembershipOrgUnitIds(target.holder())
            .contains(target.poolUnitId())) {
      return null;
    }
    for (UUID order : target.jobOrders().keySet()) {
      if (earmarkLookup.jobOrder(order) == null) {
        return null;
      }
    }
    for (UUID mission : target.missions().keySet()) {
      if (earmarkLookup.mission(mission) == null) {
        return null;
      }
    }
    return new Resolved(material, gameItem, location, lot.quality(), lot.stolen(), holder, pool);
  }

  /**
   * Reads a stock lot key back into its parts.
   *
   * @param lotKey the key, as {@code StockLot#key} writes it
   * @return the parts, or {@code null} when the key is malformed
   */
  private static @Nullable ParsedLot parse(@NotNull String lotKey) {
    List<String> parts = LOT_KEY_SEPARATOR.splitAsStream(lotKey).toList();
    if (parts.size() != 4
        || !parts.get(1).startsWith("l:")
        || !parts.get(2).startsWith("q:")
        || !parts.get(3).startsWith("s:")) {
      return null;
    }
    try {
      UUID catalogueId = UUID.fromString(parts.get(0).substring(2));
      UUID locationId = UUID.fromString(parts.get(1).substring(2));
      int quality = Integer.parseInt(parts.get(2).substring(2));
      boolean stolen = "1".equals(parts.get(3).substring(2));
      if (parts.get(0).startsWith("m:")) {
        return new ParsedLot(catalogueId, null, locationId, quality, stolen);
      }
      if (parts.get(0).startsWith("i:")) {
        return new ParsedLot(null, catalogueId, locationId, null, stolen);
      }
      return null;
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * Removes an entry the client's writes created, with its earmarks and the offers on it.
   *
   * @param row the entry
   */
  private void remove(@NotNull InventoryItem row) {
    final String label = InventoryAuditLabels.label(row);
    final UUID holder = row.getUser().getId();
    UUID id = row.getId();
    stockChangeObserver.beforeDelete(List.of(id), StockChangeReason.STOCK);
    inventoryRepository.delete(row);
    inventoryRepository.flush();
    audit(id, label, holder, 0.0, true);
  }

  /**
   * Recreates an entry the client's writes removed, under its own id.
   *
   * @param target the state to restore
   * @param resolved what it names
   */
  private void recreate(@NotNull PoolEntryState target, @NotNull Resolved resolved) {
    inventoryRepository.insertSharedEntry(
        target.id(),
        target.holder(),
        resolved.material() == null ? null : resolved.material().getId(),
        resolved.gameItem() == null ? null : resolved.gameItem().getId(),
        resolved.location().getId(),
        resolved.quality(),
        ScuAmounts.roundToScuScale(target.amount()),
        resolved.stolen(),
        target.poolUnitId());
    InventoryItem row =
        inventoryRepository
            .findById(target.id())
            .orElseThrow(() -> new IllegalStateException("The recreated entry is missing"));
    syncEarmarks(row, target);
    inventoryRepository.saveAndFlush(row);
    audit(row.getId(), InventoryAuditLabels.label(row), target.holder(), target.amount(), false);
  }

  /**
   * Gives an existing entry back its earlier holder, pool, lot, amount and earmarks; an entry that
   * already has them is left untouched.
   *
   * @param row the locked entry
   * @param target the state to restore
   * @param resolved what it names
   */
  private void rewrite(
      @NotNull InventoryItem row, @NotNull PoolEntryState target, @NotNull Resolved resolved) {
    if (sameContent(InventoryPoolEntryCommands.stateOf(row), target)) {
      return;
    }
    final double previous = InventoryPoolEntryCommands.held(row);
    double amount = ScuAmounts.roundToScuScale(target.amount());
    row.setUser(resolved.holder());
    row.setOwningOrgUnit(resolved.pool());
    row.setMaterial(resolved.material());
    row.setGameItem(resolved.gameItem());
    row.setLocation(resolved.location());
    row.setQuality(resolved.quality());
    row.setStolen(resolved.stolen());
    row.setAmount(amount);
    syncEarmarks(row, target);
    inventoryRepository.saveAndFlush(row);
    if (amount + EPSILON < previous) {
      stockChangeObserver.lower(row.getId(), amount, StockChangeReason.STOCK);
    }
    audit(row.getId(), InventoryAuditLabels.label(row), target.holder(), amount, false);
  }

  /**
   * Makes an entry's job-order and mission earmarks those of a state: removes the others, sets the
   * amounts and adds the missing ones; a job-order earmark kept keeps its delivered flag.
   *
   * @param row the entry
   * @param target the state to restore
   */
  private static void syncEarmarks(@NotNull InventoryItem row, @NotNull PoolEntryState target) {
    Map<UUID, Double> orders = new HashMap<>(target.jobOrders());
    row.getJobOrderAllocations().removeIf(slice -> !orders.containsKey(slice.getJobOrderId()));
    for (InventoryJobOrderAllocation slice : row.getJobOrderAllocations()) {
      slice.setAmount(rounded(Objects.requireNonNull(orders.remove(slice.getJobOrderId()))));
    }
    orders.forEach(
        (order, amount) -> InventoryAllocations.addJobOrder(row, order, rounded(amount), false));
    Map<UUID, Double> missions = new HashMap<>(target.missions());
    row.getMissionAllocations().removeIf(slice -> !missions.containsKey(slice.getMissionId()));
    for (InventoryMissionAllocation slice : row.getMissionAllocations()) {
      slice.setAmount(rounded(Objects.requireNonNull(missions.remove(slice.getMissionId()))));
    }
    missions.forEach(
        (mission, amount) -> InventoryAllocations.addMission(row, mission, rounded(amount)));
  }

  /**
   * Rounds an earmark's journaled amount to the SCU scale.
   *
   * @param amount the amount
   * @return the rounded amount
   */
  private static double rounded(double amount) {
    return ScuAmounts.roundToScuScale(amount);
  }

  /**
   * Audits one restored entry.
   *
   * @param id the entry
   * @param label the entry's {@code material @ location} label
   * @param holder the entry's holder
   * @param amount the amount it holds afterwards, {@code 0} when it was removed
   * @param removed whether the restore removed it
   */
  private void audit(
      @NotNull UUID id,
      @NotNull String label,
      @NotNull UUID holder,
      double amount,
      boolean removed) {
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_RESTORED,
        id,
        label,
        holder,
        AuditDetails.of("qty", amount).with("removed", removed));
  }

  /**
   * The parts of a stock lot key.
   *
   * @param materialId the material, or {@code null} for an item
   * @param gameItemId the item, or {@code null} for a material
   * @param locationId the location
   * @param quality the quality, or {@code null} for an item
   * @param stolen the stolen marker
   */
  private record ParsedLot(
      @Nullable UUID materialId,
      @Nullable UUID gameItemId,
      @NotNull UUID locationId,
      @Nullable Integer quality,
      boolean stolen) {}

  /**
   * What a state to restore names, loaded.
   *
   * @param material the material, or {@code null} for an item
   * @param gameItem the item, or {@code null} for a material
   * @param location the location
   * @param quality the quality, or {@code null} for an item
   * @param stolen the stolen marker
   * @param holder the holder
   * @param pool the org unit whose pool holds the entry
   */
  private record Resolved(
      @Nullable Material material,
      @Nullable GameItem gameItem,
      @NotNull Location location,
      @Nullable Integer quality,
      boolean stolen,
      @NotNull User holder,
      @NotNull OrgUnit pool) {}
}
