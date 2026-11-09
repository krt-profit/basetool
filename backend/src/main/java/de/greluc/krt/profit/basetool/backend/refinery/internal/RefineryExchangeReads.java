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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.City;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.catalogue.api.SpaceStation;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a member's own refinery orders for the exchange feed (REQ-XCH-051). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryExchangeReads implements RefineryExchangeReader {

  /** The statuses a snapshot serves: the orders the game still shows as running or ready. */
  private static final Set<RefineryOrderStatus> SNAPSHOT_STATUSES =
      EnumSet.of(RefineryOrderStatus.OPEN, RefineryOrderStatus.IN_PROGRESS);

  private final RefineryOrderRepository refineryOrderRepository;

  @Override
  public @NotNull @Unmodifiable List<RefineryExchangeOrder> openOrders(
      @NotNull UUID owner, @NotNull UUID after, int limit) {
    List<UUID> ids =
        refineryOrderRepository.findExchangeOrderIds(
            owner, SNAPSHOT_STATUSES, after, Limit.of(limit));
    Map<UUID, RefineryExchangeOrder> loaded =
        orders(owner, ids).stream()
            .collect(Collectors.toMap(RefineryExchangeOrder::id, Function.identity()));
    return ids.stream().map(loaded::get).filter(Objects::nonNull).toList();
  }

  @Override
  public @NotNull @Unmodifiable List<RefineryExchangeOrder> orders(
      @NotNull UUID owner, @NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return refineryOrderRepository.findExchangeOrders(owner, ids).stream()
        .map(RefineryExchangeReads::toRow)
        .toList();
  }

  @Override
  public long count(@NotNull UUID owner) {
    return refineryOrderRepository.countByOwnerId(owner);
  }

  /**
   * Maps an order with its loaded graph.
   *
   * @param order the order
   * @return the exchange row with its section counters and provenance
   */
  static @NotNull RefineryExchangeOrder toRow(@NotNull RefineryOrder order) {
    RefiningMethod method = order.getRefiningMethod();
    OrgUnit unit = order.getOwningOrgUnit();
    RefineryOrderProvenance provenance = order.getProvenance();
    return new RefineryExchangeOrder(
        order.getId(),
        order.getStatus(),
        Objects.requireNonNullElse(order.getGameDataVersion(), 0L),
        Objects.requireNonNullElse(order.getFinancesVersion(), 0L),
        provenance.getCreatedClient(),
        place(order.getLocation()),
        method == null
            ? null
            : new RefineryExchangeOrder.Method(method.getId(), method.getCode(), method.getName()),
        order.getStartedAt(),
        order.getDurationMinutes(),
        order.getEndsAt(),
        provenance.getPickedUpAt(),
        order.getStoredAt(),
        order.getExpenses(),
        order.getOtherExpenses(),
        order.getOreSales(),
        order.getMissionId(),
        unit == null ? null : unit.getId(),
        order.getGoods().stream()
            .map(RefineryExchangeReads::good)
            .sorted(Comparator.comparing(good -> good.id().toString()))
            .toList());
  }

  /**
   * Maps the refinery's location.
   *
   * @param location the location with its city and space station loaded
   * @return the place
   */
  private static @NotNull RefineryExchangeOrder.Place place(@NotNull Location location) {
    City city = location.getCity();
    SpaceStation station = location.getSpaceStation();
    return new RefineryExchangeOrder.Place(
        location.getId(),
        location.getName(),
        city == null ? null : city.getIdCity(),
        station == null ? null : station.getIdSpaceStation());
  }

  /**
   * Maps a good.
   *
   * @param good the good with its materials loaded
   * @return the good's row
   */
  private static @NotNull RefineryExchangeOrder.Good good(@NotNull RefineryGood good) {
    return new RefineryExchangeOrder.Good(
        good.getId(),
        good.getInputMaterial().getId(),
        good.getInputMaterial().getName(),
        good.getInputQuantity(),
        good.getOutputMaterial().getId(),
        good.getOutputMaterial().getName(),
        good.getOutputQuantity(),
        good.getQuality());
  }
}
