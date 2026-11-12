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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/** Simulates placement and quantity changes without constructing or mutating stock entities. */
final class InventoryLotProspectiveRows {
  private final Map<InventoryLotBatchProgram.RowKey, Row> rows = new LinkedHashMap<>();
  private final Set<UUID> resources = new HashSet<>();
  private final Map<Integer, Integer> sequences = new HashMap<>();
  private final Instant creationFloor;
  private final Map<InventoryLotBatchProgram.RowKey, Set<Integer>> selectedDependencies =
      new LinkedHashMap<>();
  private int creationOrder;
  private int step;

  /** Creates independent prospective state from immutable scalar observations. */
  InventoryLotProspectiveRows(
      InventoryStockPlanningReads.LotRows observed,
      Map<UUID, StockQuantityComposition> quantities) {
    creationFloor = Instant.now();
    for (InventoryStockRowSnapshot row : observed.entries()) {
      var key = new InventoryLotBatchProgram.Existing(row.getId());
      rows.put(
          key,
          new Row(
              key,
              new StockLot(
                  row.getMaterial(),
                  row.getGameItem(),
                  row.getLocation(),
                  row.getQuality(),
                  row.getStolen()),
              row.getPersonal(),
              row.getOwningOrgUnit() == null ? null : row.getOwningOrgUnit().getId(),
              BigDecimal.valueOf(row.getAmount()),
              new HashMap<>(row.getJobOrders()),
              row.missionReserved(),
              row.isMissionPresent(),
              row.donationReserved(),
              row.getOffer().offered(),
              row.getOffer().activeAmount(),
              row.getCreatedAt(),
              quantities.get(row.getId()),
              Set.of()));
    }
  }

  /** Copies immutable quantity proof and independent mutable physical selection facts. */
  private InventoryLotProspectiveRows(InventoryLotProspectiveRows source) {
    creationFloor = source.creationFloor;
    adopt(source);
  }

  /** Returns the existing physical resources selected by all simulated mutations. */
  Set<UUID> resources() {
    return Set.copyOf(resources);
  }

  /** Selects the stable action step that owns subsequent prospective creates. */
  void beginStep(int nextStep) {
    step = nextStep;
    selectedDependencies.clear();
  }

  /** Returns immutable accepted-prefix facts for the sources actually selected by this step. */
  Map<InventoryLotBatchProgram.RowKey, Set<Integer>> selectedDependencies() {
    return Map.copyOf(selectedDependencies);
  }

  /** Copies the physical state for effect-free whole-group prechecks. */
  InventoryLotProspectiveRows fork() {
    return new InventoryLotProspectiveRows(this);
  }

  /** Retains the complete state only after every operation of a candidate group is accepted. */
  void adopt(InventoryLotProspectiveRows candidate) {
    rows.clear();
    candidate.rows.forEach((key, row) -> rows.put(key, row.copy()));
    resources.clear();
    resources.addAll(candidate.resources);
    sequences.clear();
    sequences.putAll(candidate.sequences);
    selectedDependencies.clear();
    selectedDependencies.putAll(candidate.selectedDependencies);
    creationOrder = candidate.creationOrder;
    step = candidate.step;
  }

  /** Applies only actual accepted historical writes to the declared personal lot world. */
  void applyEntryPrefix(UUID holder, InventoryPoolRestoreProgram prefix, Set<String> declaredLots) {
    for (var entry : prefix.steps()) {
      if (!entry.decision().changes()) {
        continue;
      }
      var key = new InventoryLotBatchProgram.Existing(entry.intent().request().entryId());
      rows.remove(key);
      var target = entry.decision().targetState();
      if (target == null
          || !holder.equals(target.holder())
          || !declaredLots.contains(target.lotKey())) {
        continue;
      }
      var before = entry.before();
      var resolved = Objects.requireNonNull(entry.decision().target());
      double donations = before.map(value -> value.row().donationReserved()).orElse(0.0);
      boolean offered = before.map(value -> value.row().getOffer().offered()).orElse(false);
      double activeOffer = before.map(value -> value.row().getOffer().activeAmount()).orElse(0.0);
      Instant created = before.map(value -> value.row().getCreatedAt()).orElse(null);
      rows.put(
          key,
          new Row(
              key,
              resolved.lot(),
              false,
              target.poolUnitId(),
              BigDecimal.valueOf(target.amount()),
              new HashMap<>(target.jobOrders()),
              target.missions().values().stream().mapToDouble(Double::doubleValue).sum(),
              !target.missions().isEmpty(),
              donations,
              offered,
              Math.min(activeOffer, target.amount()),
              created,
              Objects.requireNonNull(target.completeQuantity()),
              Set.of(entry.intent().step())));
    }
  }

  /**
   * Selects the actual leading proof of a source without creating a prospective physical origin.
   */
  @Nullable
  StockQuantityComposition quantityPrefix(Row row, BigDecimal amount) {
    return row.quantity == null
        ? null
        : InventoryQuantityComposition.partition(
                row.quantity, amount.movePointRight(3).longValueExact())
            .taken();
  }

  /**
   * Returns complete current proof for a restoration source that cannot contain a new manual row.
   */
  StockQuantityComposition requireQuantity(Row row) {
    return Objects.requireNonNull(row.quantity);
  }

  /** Returns the complete evidence of every surviving row in the supported restoration form. */
  Map<InventoryLotBatchProgram.RowKey, StockQuantityComposition> quantities() {
    Map<InventoryLotBatchProgram.RowKey, StockQuantityComposition> result = new LinkedHashMap<>();
    rows.forEach((key, row) -> result.put(key, requireQuantity(row)));
    return Map.copyOf(result);
  }

  /** Returns the currently live row identified by a real or prospective key. */
  Row require(InventoryLotBatchProgram.RowKey key) {
    return Objects.requireNonNull(rows.get(key));
  }

  /** Returns the current lot rows in ordinary book-out order. */
  List<Row> lot(StockLot lot) {
    return rows.values().stream()
        .filter(row -> row.lot.key().equals(lot.key()))
        .sorted(
            Comparator.comparingInt((Row row) -> row.personal ? 0 : 1)
                .thenComparingInt(row -> row.unit == null ? 0 : 1)
                .thenComparing(oldest(false)))
        .toList();
  }

  /** Returns rebooking sources in the same order as the owning command. */
  List<Row> rebookSources(StockLot lot, UUID pool, boolean toPool) {
    return rows.values().stream()
        .filter(row -> row.lot.key().equals(lot.key()))
        .filter(row ->
            toPool ? row.personal || row.unit == null : !row.personal && pool.equals(row.unit))
        .sorted(
            toPool
                ? Comparator.comparingInt((Row row) -> row.shareGroup(pool))
                    .thenComparing(oldest(true))
                : oldest(true))
        .toList();
  }

  /** Creates a prospective unearmarked row with an action-local result key. */
  Row create(
      StockLot lot,
      boolean personal,
      @Nullable UUID unit,
      BigDecimal amount,
      @Nullable StockQuantityComposition quantity,
      Set<Integer> restoredBy) {
    int sequence = sequences.merge(step, 1, Integer::sum) - 1;
    Row row =
        new Row(
            new InventoryLotBatchProgram.Created(step, sequence),
            lot,
            personal,
            unit,
            amount,
            new HashMap<>(),
            0.0,
            false,
            0.0,
            false,
            0.0,
            creationFloor,
            quantity,
            restoredBy);
    row.creationOrder = creationOrder++;
    rows.put(row.key, row);
    return row;
  }

  /** Marks an actual mutation resource and reduces its quantity, retaining its reservations. */
  void lower(Row row, BigDecimal amount) {
    StockQuantityComposition remaining =
        row.quantity == null
            ? null
            : InventoryQuantityComposition.partition(
                    row.quantity, amount.movePointRight(3).longValueExact())
                .remaining();
    lower(row, amount, remaining);
  }

  /** Retains reservations and records the actual selected prefix dependencies. */
  private void lower(Row row, BigDecimal amount, @Nullable StockQuantityComposition remaining) {
    touch(row);
    row.quantity = remaining;
    row.amount = row.amount.subtract(amount);
    row.activeOffer = Math.min(row.activeOffer, row.amount.doubleValue());
    if (row.amount.signum() == 0) {
      rows.remove(row.key);
    }
  }

  /** Removes exact actual intervals without substituting an arbitrary physical prefix. */
  void lowerExact(Row row, StockQuantityComposition removed) {
    StockQuantityComposition before = requireQuantity(row);
    StockQuantityComposition remaining = InventoryQuantityComposition.subtract(before, removed);
    if (Math.subtractExact(before.totalQuanta(), remaining.totalQuanta())
        != removed.totalQuanta()) {
      throw new IllegalArgumentException("An exact source must contain every requested interval");
    }
    lower(row, BigDecimal.valueOf(removed.totalQuanta(), 3), remaining);
  }

  /** Marks a selected source without assigning it a new physical or quantity identity. */
  void touch(Row row) {
    selectedDependencies.put(row.key, row.restoredBy);
    if (row.key instanceof InventoryLotBatchProgram.Existing existing) {
      resources.add(existing.id());
    }
  }

  /** Changes the placement of the whole row, retaining all of its attached facts. */
  void place(Row row, StockLot lot, boolean personal, @Nullable UUID unit) {
    touch(row);
    row.lot = lot;
    row.personal = personal;
    row.unit = unit;
  }

  /** Folds the exact unoffered placement siblings into the selected survivor when applicable. */
  Set<InventoryLotBatchProgram.RowKey> merge(Row survivor, boolean merge) {
    if (!merge || survivor.offered) {
      return Set.of();
    }
    Set<InventoryLotBatchProgram.RowKey> victims = new HashSet<>();
    for (Row row : rows.values().stream().sorted(mergeOrder()).toList()) {
      if (row == survivor
          || row.offered
          || row.personal != survivor.personal
          || !Objects.equals(row.unit, survivor.unit)
          || !row.lot.key().equals(survivor.lot.key())) {
        continue;
      }
      touch(row);
      touch(survivor);
      victims.add(row.key);
      survivor.amount = survivor.amount.add(row.amount);
      if (survivor.quantity == null || row.quantity == null) {
        survivor.quantity = null;
      } else {
        var combined = new ArrayList<>(survivor.quantity.parts());
        combined.addAll(row.quantity.parts());
        survivor.quantity = InventoryQuantityComposition.canonical(combined);
      }
      Set<Integer> dependencies = new HashSet<>(survivor.restoredBy);
      dependencies.addAll(row.restoredBy);
      survivor.restoredBy = Set.copyOf(dependencies);
      row.orders.forEach((order, amount) -> survivor.orders.merge(order, amount, Double::sum));
      survivor.missions += row.missions;
      survivor.missionPresent |= row.missionPresent;
      survivor.donations += row.donations;
      rows.remove(row.key);
    }
    return Set.copyOf(victims);
  }

  /**
   * Orders existing rows by their owning timestamp and UUID convention, then prospective creates.
   */
  private static Comparator<Row> oldest(boolean javaIds) {
    return Comparator.comparing(
            (Row row) -> row.createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(identityOrder(javaIds));
  }

  /** Matches the owning SQL merge order, including its nullable creation timestamp. */
  private static Comparator<Row> mergeOrder() {
    return Comparator.comparing(
            (Row row) -> row.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(identityOrder(false));
  }

  /** Retains the owning UUID convention and relative order of action-local created rows. */
  private static Comparator<Row> identityOrder(boolean javaIds) {
    return (left, right) -> {
      if (left.key instanceof InventoryLotBatchProgram.Existing a
          && right.key instanceof InventoryLotBatchProgram.Existing b) {
        if (javaIds) {
          return a.id().compareTo(b.id());
        }
        int first =
            Long.compareUnsigned(a.id().getMostSignificantBits(), b.id().getMostSignificantBits());
        return first == 0
            ? Long.compareUnsigned(
                a.id().getLeastSignificantBits(), b.id().getLeastSignificantBits())
            : first;
      }
      if (left.key instanceof InventoryLotBatchProgram.Created
          && right.key instanceof InventoryLotBatchProgram.Created) {
        return Integer.compare(left.creationOrder, right.creationOrder);
      }
      return left.key instanceof InventoryLotBatchProgram.Existing ? -1 : 1;
    };
  }

  /**
   * Value-only row state; new rows carry no physical identity until an owning command returns one.
   */
  @Getter
  static final class Row {
    private final InventoryLotBatchProgram.RowKey key;
    private StockLot lot;
    private boolean personal;
    private @Nullable UUID unit;
    private BigDecimal amount;
    private final Map<UUID, Double> orders;
    private double missions;
    private boolean missionPresent;
    private double donations;
    private final boolean offered;
    private double activeOffer;
    private final @Nullable Instant createdAt;
    private @Nullable StockQuantityComposition quantity;
    private Set<Integer> restoredBy;
    private int creationOrder;

    /** Constructs independent physical planning facts. */
    Row(
        InventoryLotBatchProgram.RowKey key,
        StockLot lot,
        boolean personal,
        @Nullable UUID unit,
        BigDecimal amount,
        Map<UUID, Double> orders,
        double missions,
        boolean missionPresent,
        double donations,
        boolean offered,
        double activeOffer,
        @Nullable Instant createdAt,
        @Nullable StockQuantityComposition quantity,
        Set<Integer> restoredBy) {
      this.key = key;
      this.lot = lot;
      this.personal = personal;
      this.unit = unit;
      this.amount = amount;
      this.orders = orders;
      this.missions = missions;
      this.missionPresent = missionPresent;
      this.donations = donations;
      this.offered = offered;
      this.activeOffer = activeOffer;
      this.createdAt = createdAt;
      this.quantity = quantity;
      this.restoredBy = Set.copyOf(restoredBy);
    }

    /** Copies physical facts without sharing mutable allocation or dependency containers. */
    private Row copy() {
      Row copy =
          new Row(
              key,
              lot,
              personal,
              unit,
              amount,
              new HashMap<>(orders),
              missions,
              missionPresent,
              donations,
              offered,
              activeOffer,
              createdAt,
              quantity,
              restoredBy);
      copy.creationOrder = creationOrder;
      return copy;
    }

    /** Returns the canonical quantity facts used by free-prefix and stolen selection. */
    InventoryStockDecisions.DrawSource<InventoryLotBatchProgram.RowKey> drawSource() {
      return new InventoryStockDecisions.DrawSource<>(
          key,
          amount,
          BigDecimal.valueOf(orders.values().stream().mapToDouble(Double::doubleValue).sum()),
          BigDecimal.valueOf(missions),
          BigDecimal.valueOf(donations),
          lot.stolen(),
          offered);
    }

    /** Returns the canonical quantity facts used by actual and prospective rebooking. */
    InventoryStockDecisions.RebookSource<InventoryLotBatchProgram.RowKey> rebookSource() {
      return new InventoryStockDecisions.RebookSource<>(
          key,
          amount.doubleValue(),
          orders.values().stream().mapToDouble(Double::doubleValue).sum(),
          missions,
          donations,
          orders.isEmpty() && !missionPresent,
          activeOffer);
    }

    /** Returns this row's position in the owning share priority. */
    private int shareGroup(UUID pool) {
      return !personal ? 3 : unit == null ? 1 : pool.equals(unit) ? 0 : 2;
    }
  }
}
