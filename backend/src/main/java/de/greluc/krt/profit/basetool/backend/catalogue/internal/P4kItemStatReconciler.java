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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintStatProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStat;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Writes the blueprint stat base values of every catalogue item the run matched or created from the
 * item's {@code stats} (REQ-P4K-030, ADR-0342); runs after the items are reconciled and only on an
 * APPLY. A GUID the catalogue repeats is written from its first record. A stored P4K value the item
 * no longer carries is removed only when the catalogue's items are complete.
 */
@Component
@RequiredArgsConstructor
public class P4kItemStatReconciler {

  /** The most item ids one read of the stored values names. */
  static final int READ_CHUNK = 1_000;

  /** Reads the stored values of the run's items. */
  private final GameItemStatRepository statRepository;

  /** Stores the values with the P4K as their source. */
  private final GameItemStatWriter statWriter;

  /**
   * Writes the values of every item the run matched or created.
   *
   * @param catalogue the catalogue
   * @param context the run, whose items are reconciled
   */
  public void reconcile(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    if (!context.isApply()) {
      return;
    }
    boolean removeMissing = catalogue.head().isComplete(P4kKind.ITEM);
    Map<UUID, P4kKeyedRow> rows = context.stampedRows(P4kKind.ITEM);
    Map<UUID, List<GameItemStat>> stored = storedValues(rows);
    Set<UUID> written = new HashSet<>();
    for (P4kItemDto item : catalogue.items()) {
      UUID guid = P4kGuids.parse(item.guid());
      if (guid == null || !(rows.get(guid) instanceof GameItem row) || !written.add(guid)) {
        continue;
      }
      Map<BlueprintStatProperty, Double> values = P4kItemStatExtractor.extract(item.stats());
      List<GameItemStat> current =
          row.getId() == null ? List.of() : stored.getOrDefault(row.getId(), List.of());
      if (!values.isEmpty() || !current.isEmpty()) {
        statWriter.writeP4k(row, values, current, removeMissing);
      }
    }
  }

  /**
   * Reads the stored values of the run's items in chunks of {@value #READ_CHUNK} ids.
   *
   * @param rows the run's item rows by GUID
   * @return the stored values by item id
   */
  @NotNull
  private Map<UUID, List<GameItemStat>> storedValues(@NotNull Map<UUID, P4kKeyedRow> rows) {
    List<UUID> ids =
        rows.values().stream()
            .filter(GameItem.class::isInstance)
            .map(row -> ((GameItem) row).getId())
            .filter(Objects::nonNull)
            .toList();
    Map<UUID, List<GameItemStat>> byItem = new HashMap<>();
    for (int from = 0; from < ids.size(); from += READ_CHUNK) {
      List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + READ_CHUNK));
      for (GameItemStat stat : statRepository.findByGameItemIdIn(chunk)) {
        byItem.computeIfAbsent(stat.getGameItem().getId(), _ -> new ArrayList<>()).add(stat);
      }
    }
    return byItem;
  }
}
