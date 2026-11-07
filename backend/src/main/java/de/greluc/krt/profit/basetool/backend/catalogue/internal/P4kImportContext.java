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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

  /** The report the run fills. */
  @Getter @NotNull private final P4kImportReport report = new P4kImportReport();

  /** The matched manufacturers by P4K GUID, for the items and ships of the run. */
  private final Map<UUID, Manufacturer> manufacturerByGuid = new HashMap<>();

  /** The matched or created resource groups by P4K GUID, for the commodities of the run. */
  private final Map<UUID, MaterialCategory> resourceGroupByGuid = new HashMap<>();

  /** The GUID that claimed each row in this run, per kind. */
  private final Map<P4kKind, Map<UUID, UUID>> claims = new EnumMap<>(P4kKind.class);

  /** The lower-cased match keys several records of a kind share; they never match by fallback. */
  private final Map<P4kKind, Set<String>> repeatedKeys = new EnumMap<>(P4kKind.class);

  /**
   * Creates the context of one run.
   *
   * @param catalogue the catalogue, scanned for repeated class names and names
   * @param apply whether the run writes
   * @param now the apply time
   * @param runId the run of an APPLY, {@code null} for a preview
   * @param createItemsAndShips whether unmatched playable items and ships are created
   */
  public P4kImportContext(
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
