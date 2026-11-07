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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Writes the blueprint stat base values of every item a catalogue blueprint produces from the
 * item's {@code stats} (REQ-P4K-030, ADR-0342); runs after the items are reconciled and only on an
 * APPLY.
 */
@Component
@RequiredArgsConstructor
public class P4kItemStatReconciler {

  /** Stores the values with the P4K as their source. */
  private final GameItemStatWriter statWriter;

  /**
   * Writes the values of every produced item the run matched or created.
   *
   * @param catalogue the catalogue
   * @param context the run, whose items are reconciled
   */
  public void reconcile(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    if (!context.isApply()) {
      return;
    }
    Set<UUID> produced =
        catalogue.blueprints().stream()
            .map(P4kBlueprintDto::producedItemGuid)
            .map(P4kGuids::parse)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, P4kKeyedRow> rows = context.stampedRows(P4kKind.ITEM);
    for (P4kItemDto item : catalogue.items()) {
      UUID guid = P4kGuids.parse(item.guid());
      if (guid != null && produced.contains(guid) && rows.get(guid) instanceof GameItem row) {
        statWriter.writeP4k(row, P4kItemStatExtractor.extract(item.stats()));
      }
    }
  }
}
