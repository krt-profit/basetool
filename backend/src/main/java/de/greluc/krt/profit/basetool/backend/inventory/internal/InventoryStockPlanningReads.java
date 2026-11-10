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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads current physical planning facts in batches without refreshing managed stock entities. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class InventoryStockPlanningReads {
  private final InventoryItemRepository rows;
  private final EntityManager entityManager;
  private final StockOfferLookup offers;

  /** Immutable physical snapshots for all declared lots, retaining their ordinary source order. */
  record LotRows(Map<String, List<InventoryStockRowSnapshot>> rows) {
    LotRows {
      Map<String, List<InventoryStockRowSnapshot>> copy = new LinkedHashMap<>();
      rows.forEach((key, value) -> copy.put(key, List.copyOf(value)));
      rows = Map.copyOf(copy);
    }

    Map<String, List<StockRow>> views() {
      Map<String, List<StockRow>> result = new LinkedHashMap<>();
      rows.forEach((key, value) -> result.put(key, List.copyOf(value)));
      return Map.copyOf(result);
    }

    List<InventoryStockRowSnapshot> entries() {
      return rows.values().stream().flatMap(Collection::stream).toList();
    }
  }

  LotRows read(@NotNull UUID holder, @NotNull Collection<StockLot> lots) {
    Map<String, List<InventoryStockRowSnapshot>> result = new LinkedHashMap<>();
    lots.forEach(lot -> result.putIfAbsent(lot.key(), new ArrayList<>()));
    if (result.isEmpty()) {
      return new LotRows(Map.of());
    }
    List<UUID> ids = rows.findRowsInHolderLots(holder, result.keySet());
    if (ids.isEmpty()) {
      result.replaceAll((_, _) -> List.of());
      return new LotRows(Map.copyOf(result));
    }
    Map<UUID, Map<UUID, Double>> orders = new HashMap<>();
    for (var allocation : rows.findOrderAllocationFacts(ids)) {
      orders
          .computeIfAbsent(allocation.getRowId(), _ -> new HashMap<>())
          .put(allocation.getOrderId(), allocation.getAmount());
    }
    Map<UUID, Double> missions = reservations(rows.findPlanningMissionReservations(ids));
    Map<UUID, Double> donations = reservations(rows.findPlanningDonationReservations(ids));
    var offerFacts = offers.offerFacts(ids);
    for (InventoryOrderAllocationQueries.RowLot row : rows.findRowLots(ids)) {
      String key =
          StockLot.key(
              row.getMaterialId(),
              row.getGameItemId(),
              row.getLocationId(),
              row.getQuality(),
              Boolean.TRUE.equals(row.getStolen()));
      List<InventoryStockRowSnapshot> lotRows = result.get(key);
      if (lotRows == null) {
        throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
            InventoryItem.class, row.getId());
      }
      lotRows.add(
          new InventoryStockRowSnapshot(
              row.getId(),
              Objects.requireNonNull(row.getVersion()),
              entityManager.getReference(User.class, row.getHolderId()),
              row.getOwningOrgUnitId() == null
                  ? null
                  : entityManager.getReference(OrgUnit.class, row.getOwningOrgUnitId()),
              row.getMaterialId() == null
                  ? null
                  : entityManager.getReference(Material.class, row.getMaterialId()),
              row.getGameItemId() == null
                  ? null
                  : entityManager.getReference(GameItem.class, row.getGameItemId()),
              entityManager.getReference(Location.class, row.getLocationId()),
              row.getQuality(),
              row.getAmount(),
              Boolean.TRUE.equals(row.getStolen()),
              Boolean.TRUE.equals(row.getPersonal()),
              Map.copyOf(orders.getOrDefault(row.getId(), Map.of())),
              missions.getOrDefault(row.getId(), 0.0),
              missions.containsKey(row.getId()),
              donations.getOrDefault(row.getId(), 0.0),
              row.getCreatedAt(),
              offerFacts.getOrDefault(row.getId(), StockOfferLookup.StockOfferFact.NONE)));
    }
    result.replaceAll((_, entries) -> {
      entries.sort(sourceOrder());
      return List.copyOf(entries);
    });
    return new LotRows(Map.copyOf(result));
  }

  private static Map<UUID, Double> reservations(
      List<InventoryOrderAllocationQueries.ReservationFact> facts) {
    Map<UUID, Double> amounts = new HashMap<>();
    facts.forEach(fact -> amounts.put(fact.getRowId(), fact.getAmount()));
    return amounts;
  }

  private static Comparator<InventoryStockRowSnapshot> sourceOrder() {
    return Comparator.comparingInt(
            (InventoryStockRowSnapshot row) -> Boolean.TRUE.equals(row.getPersonal()) ? 0 : 1)
        .thenComparingInt(row -> row.getOwningOrgUnit() == null ? 0 : 1)
        .thenComparing(
            InventoryStockRowSnapshot::getCreatedAt,
            Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(InventoryStockRowSnapshot::getId, InventoryStockPlanningReads::compareIds);
  }

  private static int compareIds(UUID left, UUID right) {
    int first = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return first == 0
        ? Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits())
        : first;
  }
}
