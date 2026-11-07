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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kPaintDto;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code ship_type_paint} in step with the catalogue's {@code paints} (REQ-P4K-041): the
 * paint itself is an item the item reconciler matched, and an APPLY sets the ship links of every
 * listed paint to the ship types its {@code shipGuids} resolve to. A complete paints kind also
 * removes the links of paints it no longer lists; an empty one removes nothing.
 */
@Component
@RequiredArgsConstructor
class P4kPaintReconciler {

  private final ShipTypePaintRepository paintRepository;
  private final EntityManager entityManager;

  /**
   * Links the paints of a run; items and ships must be reconciled first. A preview counts the links
   * and writes nothing.
   *
   * @param paints the catalogue's paints
   * @param complete whether the paints kind is complete
   * @param context the run
   */
  void reconcile(
      @NotNull List<P4kPaintDto> paints, boolean complete, @NotNull P4kImportContext context) {
    P4kRowIds items = P4kRowIds.load(entityManager, P4kKind.ITEM, context);
    P4kRowIds ships = P4kRowIds.load(entityManager, P4kKind.SHIP, context);
    Map<UUID, Map<UUID, ShipTypePaint>> stored = storedLinks();
    Set<UUID> listed = new HashSet<>();
    List<ShipTypePaint> gone = new ArrayList<>();
    for (P4kPaintDto paint : paints) {
      UUID itemId = items.id(paint.itemGuid());
      if (itemId == null || !listed.add(itemId)) {
        continue;
      }
      Set<UUID> fits = new HashSet<>();
      for (String shipGuid : paint.shipGuids() == null ? List.<String>of() : paint.shipGuids()) {
        UUID shipId = ships.id(shipGuid);
        if (shipId != null) {
          fits.add(shipId);
        }
      }
      context.getReport().paintLinked(fits.size());
      if (context.isApply()) {
        Map<UUID, ShipTypePaint> current = stored.getOrDefault(itemId, Map.of());
        P4kLinkDiff<UUID> diff = P4kLinkDiff.of(fits, current.keySet());
        diff.removed().forEach(shipId -> gone.add(current.get(shipId)));
        diff.added().forEach(shipId -> entityManager.persist(new ShipTypePaint(shipId, itemId)));
      }
    }
    if (context.isApply() && complete && !paints.isEmpty()) {
      stored.forEach((itemId, links) -> {
        if (!listed.contains(itemId)) {
          gone.addAll(links.values());
        }
      });
    }
    P4kLinkDiff.deleteInChunks(gone, paintRepository::deleteAllInBatch);
  }

  /**
   * Reads the stored links, grouped by paint and ship type.
   *
   * @return the links per paint item and ship type
   */
  @NotNull
  private Map<UUID, Map<UUID, ShipTypePaint>> storedLinks() {
    Map<UUID, Map<UUID, ShipTypePaint>> stored = new HashMap<>();
    for (ShipTypePaint link : paintRepository.findAll()) {
      stored
          .computeIfAbsent(link.getGameItemId(), _ -> new HashMap<>())
          .put(link.getShipTypeId(), link);
    }
    return stored;
  }
}
