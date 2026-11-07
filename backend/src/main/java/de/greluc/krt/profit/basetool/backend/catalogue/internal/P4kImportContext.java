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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The state one P4K run shares across its per-kind reconcilers: whether it writes, its run, the
 * "create new items and ships" choice, the report, the manufacturer and resource-group indexes, the
 * rows claimed so far and the class names and names the catalogue repeats within a kind.
 */
public final class P4kImportContext {

  /** Whether the run writes (APPLY) or only computes (preview). */
  @Getter private final boolean apply;

  /** The apply time stamped on matched rows. */
  @Getter @NotNull private final Instant now;

  /** The run of an APPLY, {@code null} for a preview. */
  @Getter @Nullable private final UUID runId;

  /** Whether unmatched playable items and ships are created (REQ-P4K-009). */
  @Getter private final boolean createItemsAndShips;

  /** The admin categories the APPLY deleted once no material referenced them (REQ-P4K-025). */
  @Getter @Setter private int adminCategoriesRetired;

  /** The report the run fills. */
  @Getter @NotNull private final P4kImportReport report = new P4kImportReport();

  /** The matched manufacturers by P4K GUID, for the items and ships of the run. */
  private final Map<UUID, Manufacturer> manufacturerByGuid = new HashMap<>();

  /** The matched or created resource groups by P4K GUID, for the commodities of the run. */
  private final Map<UUID, MaterialCategory> resourceGroupByGuid = new HashMap<>();

  /** The matched or created blueprint categories by P4K GUID, for the blueprints of the run. */
  private final Map<UUID, BlueprintCategory> blueprintCategoryByGuid = new HashMap<>();

  /**
   * The unmatched manufacturers by P4K GUID, held until the items and ships of the run decide
   * whether a seeded row references them (REQ-P4K-021).
   */
  private final Map<UUID, P4kManufacturerDto> unmatchedManufacturers = new LinkedHashMap<>();

  /**
   * The manufacturer names the run wrote or seeded, lower-cased, with the holding row's id; the id
   * is {@code null} for a preview's seed, which creates no row.
   */
  private final Map<String, UUID> manufacturerNames = new HashMap<>();

  /** The manufacturer rows, indexed once when the manufacturers are reconciled. */
  @Nullable private P4kRowIndex<Manufacturer> manufacturerRows;

  /** The item rows, indexed once when the items are reconciled. */
  @Nullable private P4kRowIndex<GameItem> itemRows;

  /** The ship-type rows, indexed once when the ships are reconciled. */
  @Nullable private P4kRowIndex<ShipType> shipRows;

  /** The material rows, indexed once when the commodities are reconciled. */
  @Nullable private P4kRowIndex<Material> commodityRows;

  /** The GUID that claimed each row in this run, per kind. */
  private final Map<P4kKind, Map<UUID, UUID>> claims = new EnumMap<>(P4kKind.class);

  /** The rows the run matched or created, per kind and P4K GUID. */
  private final Map<P4kKind, Map<UUID, P4kKeyedRow>> stamped = new EnumMap<>(P4kKind.class);

  /** The lower-cased match keys several records of a kind share; they never match by fallback. */
  private final Map<P4kKind, Set<String>> repeatedKeys = new EnumMap<>(P4kKind.class);

  /**
   * Creates the context of a preview: it writes nothing, has no run and counts what "create new
   * items and ships" would create (REQ-P4K-009).
   *
   * @param catalogue the catalogue, scanned for repeated class names and names
   * @param now the time the preview runs at
   * @return the context
   */
  @NotNull
  public static P4kImportContext forPreview(@NotNull P4kCatalogue catalogue, @NotNull Instant now) {
    return new P4kImportContext(catalogue, false, now, null, true);
  }

  /**
   * Creates the context of an APPLY.
   *
   * @param catalogue the catalogue, scanned for repeated class names and names
   * @param now the apply time stamped on matched rows
   * @param runId the run being recorded
   * @param createItemsAndShips whether unmatched playable items and ships are created
   * @return the context
   */
  @NotNull
  public static P4kImportContext forApply(
      @NotNull P4kCatalogue catalogue,
      @NotNull Instant now,
      @NotNull UUID runId,
      boolean createItemsAndShips) {
    return new P4kImportContext(catalogue, true, now, runId, createItemsAndShips);
  }

  /**
   * Creates the context of one run.
   *
   * @param catalogue the catalogue, scanned for repeated class names and names
   * @param apply whether the run writes
   * @param now the apply time
   * @param runId the run of an APPLY, {@code null} for a preview
   * @param createItemsAndShips whether unmatched playable items and ships are created
   */
  private P4kImportContext(
      @NotNull P4kCatalogue catalogue,
      boolean apply,
      @NotNull Instant now,
      @Nullable UUID runId,
      boolean createItemsAndShips) {
    this.apply = apply;
    this.now = now;
    this.runId = runId;
    this.createItemsAndShips = createItemsAndShips;
    repeatedKeys.put(P4kKind.MANUFACTURER, repeated(catalogue.manufacturers()));
    repeatedKeys.put(P4kKind.ITEM, repeated(catalogue.items()));
    repeatedKeys.put(P4kKind.SHIP, repeated(catalogue.ships()));
    repeatedKeys.put(P4kKind.COMMODITY, repeated(catalogue.commodities()));
    repeatedKeys.put(P4kKind.BLUEPRINT, repeated(catalogue.blueprints()));
    repeatedKeys.put(P4kKind.RESOURCE_GROUP, repeated(catalogue.resourceGroups()));
    repeatedKeys.put(P4kKind.BLUEPRINT_CATEGORY, repeated(catalogue.blueprintCategories()));
  }

  /**
   * Whether several records of a kind share a match key, so the key identifies none of them.
   *
   * @param kind the kind
   * @param key a class name or name, or {@code null}
   * @return {@code true} when the key occurs more than once, ignoring case
   */
  public boolean isRepeated(@NotNull P4kKind kind, @Nullable String key) {
    return key != null && repeatedKeys.getOrDefault(kind, Set.of()).contains(normalise(key));
  }

  /**
   * Claims a row for a GUID within this run.
   *
   * @param kind the kind
   * @param rowId the row
   * @param guid the GUID claiming it
   * @return {@code false} when another GUID already claimed the row in this run
   */
  public boolean claim(@NotNull P4kKind kind, @NotNull UUID rowId, @NotNull UUID guid) {
    UUID previous = claims.computeIfAbsent(kind, _ -> new HashMap<>()).putIfAbsent(rowId, guid);
    return previous == null || previous.equals(guid);
  }

  /**
   * Stamps a matched or created row with its GUID, the apply time and the run (REQ-P4K-005,
   * REQ-P4K-007), and clears the sweep's stamp of a row that reappeared (REQ-P4K-011); a preview
   * only reports the reappearance and writes nothing.
   *
   * @param kind the kind
   * @param record the record that matched the row
   * @param row the row
   * @param guid the P4K GUID
   */
  public void stamp(
      @NotNull P4kKind kind,
      @NotNull P4kRecord record,
      @NotNull P4kKeyedRow row,
      @NotNull UUID guid) {
    stamped.computeIfAbsent(kind, _ -> new HashMap<>()).put(guid, row);
    if (row.getP4kDeletedAt() != null) {
      report.reappeared(kind, record);
      if (apply) {
        row.setP4kDeletedAt(null);
      }
    }
    if (!apply) {
      return;
    }
    if (!Objects.equals(row.getP4kUuid(), guid)) {
      row.setP4kUuid(guid);
    }
    row.setP4kSyncedAt(now);
    row.setP4kSeenRunId(runId);
  }

  /**
   * Returns the rows the run matched or created so far, by P4K GUID; a preview holds the rows it
   * matched although it writes no GUID onto them.
   *
   * @param kind the kind
   * @return the rows by GUID; a row created by an APPLY has its id once it is saved
   */
  @NotNull
  @Unmodifiable
  public Map<UUID, P4kKeyedRow> stampedRows(@NotNull P4kKind kind) {
    return Map.copyOf(stamped.getOrDefault(kind, Map.of()));
  }

  /**
   * Keeps the manufacturer rows the manufacturer reconciler loaded for the rest of the run.
   *
   * @param rows the index
   */
  public void indexManufacturerRows(@NotNull P4kRowIndex<Manufacturer> rows) {
    manufacturerRows = rows;
  }

  /**
   * Returns the manufacturer rows; the manufacturers must be reconciled first.
   *
   * @return the index
   * @throws IllegalStateException before the manufacturers are reconciled
   */
  @NotNull
  public P4kRowIndex<Manufacturer> manufacturerRows() {
    return indexed(manufacturerRows, P4kKind.MANUFACTURER);
  }

  /**
   * Keeps the item rows the item reconciler loaded for the rest of the run.
   *
   * @param rows the index
   */
  public void indexItemRows(@NotNull P4kRowIndex<GameItem> rows) {
    itemRows = rows;
  }

  /**
   * Returns the item rows; the items must be reconciled first.
   *
   * @return the index
   * @throws IllegalStateException before the items are reconciled
   */
  @NotNull
  public P4kRowIndex<GameItem> itemRows() {
    return indexed(itemRows, P4kKind.ITEM);
  }

  /**
   * Keeps the ship-type rows the ship reconciler loaded for the rest of the run.
   *
   * @param rows the index
   */
  public void indexShipRows(@NotNull P4kRowIndex<ShipType> rows) {
    shipRows = rows;
  }

  /**
   * Returns the ship-type rows; the ships must be reconciled first.
   *
   * @return the index
   * @throws IllegalStateException before the ships are reconciled
   */
  @NotNull
  public P4kRowIndex<ShipType> shipRows() {
    return indexed(shipRows, P4kKind.SHIP);
  }

  /**
   * Keeps the material rows the commodity reconciler loaded for the rest of the run.
   *
   * @param rows the index
   */
  public void indexCommodityRows(@NotNull P4kRowIndex<Material> rows) {
    commodityRows = rows;
  }

  /**
   * Returns the material rows; the commodities must be reconciled first.
   *
   * @return the index
   * @throws IllegalStateException before the commodities are reconciled
   */
  @NotNull
  public P4kRowIndex<Material> commodityRows() {
    return indexed(commodityRows, P4kKind.COMMODITY);
  }

  /**
   * Returns an index the run built.
   *
   * @param rows the index, or {@code null} before its kind is reconciled
   * @param kind the kind, for the error
   * @param <T> the row type
   * @return the index
   * @throws IllegalStateException before the kind is reconciled
   */
  @NotNull
  private static <T extends P4kKeyedRow> P4kRowIndex<T> indexed(
      @Nullable P4kRowIndex<T> rows, @NotNull P4kKind kind) {
    if (rows == null) {
      throw new IllegalStateException("The " + kind + " rows are read before they are indexed.");
    }
    return rows;
  }

  /**
   * Indexes a matched manufacturer for the items and ships of the run.
   *
   * @param guid the manufacturer's GUID
   * @param manufacturer the manufacturer
   */
  public void indexManufacturer(@NotNull UUID guid, @NotNull Manufacturer manufacturer) {
    manufacturerByGuid.put(guid, manufacturer);
  }

  /**
   * Returns a manufacturer the run matched.
   *
   * @param guid the manufacturer's GUID text, or {@code null}
   * @return the manufacturer, or {@code null}
   */
  @Nullable
  public Manufacturer manufacturer(@Nullable String guid) {
    UUID parsed = P4kGuids.parse(guid);
    return parsed == null ? null : manufacturerByGuid.get(parsed);
  }

  /**
   * Indexes a matched or created resource group for the commodities of the run.
   *
   * @param guid the group's GUID
   * @param group the group
   */
  public void indexResourceGroup(@NotNull UUID guid, @NotNull MaterialCategory group) {
    resourceGroupByGuid.put(guid, group);
  }

  /**
   * Returns a resource group the run matched or created.
   *
   * @param guid the group's GUID text, or {@code null}
   * @return the group, or {@code null}
   */
  @Nullable
  public MaterialCategory resourceGroup(@Nullable String guid) {
    UUID parsed = P4kGuids.parse(guid);
    return parsed == null ? null : resourceGroupByGuid.get(parsed);
  }

  /**
   * Indexes a matched or created blueprint category for the blueprints of the run.
   *
   * @param guid the category's GUID
   * @param category the category
   */
  public void indexBlueprintCategory(@NotNull UUID guid, @NotNull BlueprintCategory category) {
    blueprintCategoryByGuid.put(guid, category);
  }

  /**
   * Returns a blueprint category the run matched or created.
   *
   * @param guid the category's GUID text, or {@code null}
   * @return the category, or {@code null}
   */
  @Nullable
  public BlueprintCategory blueprintCategory(@Nullable String guid) {
    UUID parsed = P4kGuids.parse(guid);
    return parsed == null ? null : blueprintCategoryByGuid.get(parsed);
  }

  /**
   * Holds an unmatched manufacturer until the items and ships of the run are reconciled.
   *
   * @param guid the manufacturer's GUID
   * @param manufacturer the record
   */
  public void holdUnmatchedManufacturer(
      @NotNull UUID guid, @NotNull P4kManufacturerDto manufacturer) {
    unmatchedManufacturers.put(guid, manufacturer);
  }

  /**
   * Takes a held unmatched manufacturer for a seeded item or ship that references it.
   *
   * @param guid the manufacturer's GUID text, or {@code null}
   * @return the record, or {@code null} when none is held for the GUID
   */
  @Nullable
  public P4kManufacturerDto takeUnmatchedManufacturer(@Nullable String guid) {
    UUID parsed = P4kGuids.parse(guid);
    return parsed == null ? null : unmatchedManufacturers.remove(parsed);
  }

  /**
   * Records that the run gave a manufacturer row a name, so a later seed of that name is a conflict
   * (REQ-P4K-021).
   *
   * @param name the name
   * @param rowId the holding row, {@code null} for a preview's seed
   */
  public void claimManufacturerName(@NotNull String name, @Nullable UUID rowId) {
    manufacturerNames.put(normalise(name), rowId);
  }

  /**
   * Whether the run already gave a manufacturer row this name.
   *
   * @param name the name
   * @return {@code true} when a written or seeded row of the run holds it, ignoring case
   */
  public boolean claimsManufacturerName(@NotNull String name) {
    return manufacturerNames.containsKey(normalise(name));
  }

  /**
   * Returns the row the run gave this manufacturer name.
   *
   * @param name the name
   * @return the row's id, or {@code null} when no row of the run holds it or a preview seeded it
   */
  @Nullable
  public UUID manufacturerNameRow(@NotNull String name) {
    return manufacturerNames.get(normalise(name));
  }

  /**
   * Returns the unmatched manufacturers no seeded row took.
   *
   * @return the records in catalogue order
   */
  @NotNull
  @Unmodifiable
  public List<P4kManufacturerDto> heldUnmatchedManufacturers() {
    return List.copyOf(unmatchedManufacturers.values());
  }

  /**
   * Collects the class names and names several records share.
   *
   * @param records the records of a kind
   * @return the repeated keys, lower-cased
   */
  @NotNull
  private static Set<String> repeated(@NotNull List<? extends P4kRecord> records) {
    Set<String> seen = new HashSet<>();
    Set<String> repeated = new HashSet<>();
    List<Function<P4kRecord, String>> keys = List.of(P4kRecord::className, P4kRecord::nameEn);
    for (Function<P4kRecord, String> key : keys) {
      seen.clear();
      for (P4kRecord record : records) {
        String value = key.apply(record);
        if (value != null && !value.isBlank() && !seen.add(normalise(value))) {
          repeated.add(normalise(value));
        }
      }
    }
    return repeated;
  }

  /**
   * Normalises a match key.
   *
   * @param key the key
   * @return the trimmed, lower-cased key
   */
  @NotNull
  private static String normalise(@NotNull String key) {
    return key.strip().toLowerCase(Locale.ROOT);
  }
}
