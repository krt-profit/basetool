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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository.ExchangeLocationGuid;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRefineryOrderPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeOrder;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own refinery orders as a snapshot and a change feed for exchange clients
 * (REQ-XCH-051, ADR-0351, ADR-0224).
 *
 * <p>A snapshot pages through the member's open and running orders by id and ends with the feed
 * position it was taken at; the feed answers each order changed after the cursor once, whatever its
 * status, with its current state, or with a tombstone once the order is deleted or owned by another
 * member.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRefineryOrderFeedService {

  private final RefineryExchangeReader refineryReader;
  private final ExchangeFeedReader feedReader;
  private final LocationRepository locationRepository;
  private final KnownExchangeClients knownClients;
  private final ExchangeRefineryOrderLinkRepository linkRepository;

  /**
   * Returns one page of the member's refinery orders.
   *
   * @param caller the client, installation and member
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeRefineryOrderPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    return feedReader.answer(
        cursor,
        limit,
        (at, afterId, size) -> snapshot(caller, at, afterId, size),
        (after, size) -> feed(caller, after, size));
  }

  /**
   * Reads one snapshot page.
   *
   * @param caller the caller
   * @param at the feed position the snapshot was taken at
   * @param afterId the last order delivered
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeRefineryOrderPageDto snapshot(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeFeedPosition at,
      @NotNull UUID afterId,
      int size) {
    ExchangeFeedReader.Slice<RefineryExchangeOrder> page =
        ExchangeFeedReader.slice(
            refineryReader.openOrders(caller.member(), afterId, size + 1),
            size,
            at,
            RefineryExchangeOrder::id);
    References refs =
        references(
            caller, page.items().stream().map(RefineryExchangeOrder::id).toList(), page.items());
    return new ExchangeRefineryOrderPageDto(
        page.items().stream().map(order -> toDto(order, refs)).toList(),
        List.of(),
        page.next(),
        page.more());
  }

  /**
   * Reads one feed page: each order changed after the position, once, with its current state or a
   * tombstone.
   *
   * @param caller the caller
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeRefineryOrderPageDto feed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeFeedPosition after, int size) {
    UUID member = caller.member();
    ExchangeFeedReader.Changes changes =
        feedReader.changes(member, ExchangeFeedResource.REFINERY_ORDER, after, size);
    Map<String, RefineryExchangeOrder> current =
        refineryReader
            .orders(member, changes.keys().stream().map(UUID::fromString).toList())
            .stream()
            .collect(Collectors.toMap(order -> order.id().toString(), Function.identity()));
    References refs =
        references(
            caller, changes.keys().stream().map(UUID::fromString).toList(), current.values());
    List<ExchangeRefineryOrderDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      RefineryExchangeOrder order = current.get(key);
      if (order != null) {
        items.add(toDto(order, refs));
      } else {
        removed.add(changes.tombstone(key, key).withClientKey(refs.removedKeys().get(key)));
      }
    }
    return new ExchangeRefineryOrderPageDto(items, removed, changes.nextCursor(), changes.more());
  }

  /**
   * Reads what the page names by id: its orders' locations' starmap GUIDs, their creating clients'
   * display names and the caller's own keys of every delivered key, one query each.
   *
   * @param caller the caller, whose client alone sees its keys
   * @param keys every order the page delivers, a removed one included
   * @param orders the orders the page serves with their state
   * @return the references
   */
  private @NotNull References references(
      @NotNull ExchangeCaller caller,
      @NotNull Collection<UUID> keys,
      @NotNull Collection<RefineryExchangeOrder> orders) {
    List<ExchangeRefineryOrderLink> links =
        keys.isEmpty()
            ? List.of()
            : linkRepository.findByUserIdAndClientIdAndRefineryOrderIdIn(
                caller.member(), caller.clientId(), keys);
    return new References(
        guids(orders),
        clientNames(orders),
        keysByOrder(links.stream().filter(link -> link.getRetiredAt() == null).toList()),
        keysByOrder(links));
  }

  /**
   * Maps links to their keys by order id.
   *
   * @param links the links, at most one per order
   * @return the client key by order id
   */
  private static @NotNull Map<String, String> keysByOrder(
      @NotNull List<ExchangeRefineryOrderLink> links) {
    return links.stream()
        .collect(
            Collectors.toMap(
                link -> link.getRefineryOrderId().toString(),
                ExchangeRefineryOrderLink::getClientKey));
  }

  /**
   * Reads the display names of the given orders' creating clients in one query.
   *
   * @param orders the orders
   * @return the display name by client id; a client the registry no longer holds is absent
   */
  private @NotNull Map<String, String> clientNames(
      @NotNull Collection<RefineryExchangeOrder> orders) {
    Set<String> clients =
        orders.stream()
            .map(RefineryExchangeOrder::createdByClient)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return clients.isEmpty() ? Map.of() : knownClients.displayNames(clients);
  }

  /**
   * Reads the starmap GUIDs of the given orders' locations in one query.
   *
   * @param orders the orders
   * @return the GUID by location id; a location without one is absent
   */
  private @NotNull Map<UUID, UUID> guids(@NotNull Collection<RefineryExchangeOrder> orders) {
    Set<UUID> locations =
        orders.stream().map(order -> order.location().id()).collect(Collectors.toSet());
    if (locations.isEmpty()) {
      return Map.of();
    }
    return locationRepository.findExchangeGuids(locations).stream()
        .collect(
            Collectors.toMap(ExchangeLocationGuid::getLocationId, ExchangeLocationGuid::getGuid));
  }

  /**
   * Builds an order's feed entry: the caller's own key, the creating client's display name and the
   * location's starmap GUID from the page's references, names cut to the contract's length.
   *
   * @param order the order
   * @param refs the page's location GUIDs, client names and keys
   * @return the feed entry
   */
  private static @NotNull ExchangeRefineryOrderDto toDto(
      @NotNull RefineryExchangeOrder order, @NotNull References refs) {
    RefineryExchangeOrder.Place place = order.location();
    RefineryExchangeOrder.Method method = order.method();
    String creator = order.createdByClient();
    return new ExchangeRefineryOrderDto(
        order.id().toString(),
        refs.clientKeys().get(order.id().toString()),
        creator == null
            ? null
            : new ExchangeRefineryOrderDto.Creator(
                creator,
                ExchangeStockFeedService.cut(refs.clientNames().getOrDefault(creator, creator))),
        new ExchangeRefineryOrderDto.Versions(order.gameDataVersion(), order.financesVersion()),
        order.status().name(),
        new ExchangeLocationDto(
            ExchangeStockFeedService.cut(place.name()),
            ExchangeCatalogService.uex(place.uexCityId(), place.uexSpaceStationId()),
            refs.guids().get(place.id())),
        method == null
            ? null
            : new ExchangeRefineryOrderDto.Method(
                method.code(), ExchangeStockFeedService.cut(method.name())),
        order.startedAt(),
        order.durationMinutes(),
        order.endsAt(),
        order.pickedUpAt(),
        order.storedAt(),
        order.expenses(),
        order.otherExpenses(),
        order.oreSales(),
        order.missionId(),
        order.orgUnitId(),
        order.goods().stream().map(ExchangeRefineryOrderFeedService::good).toList());
  }

  /**
   * Builds a good's feed entry, its materials as item references with their names cut to the
   * contract's length.
   *
   * @param good the good
   * @return the feed's good
   */
  private static @NotNull ExchangeRefineryOrderDto.Good good(
      @NotNull RefineryExchangeOrder.Good good) {
    return new ExchangeRefineryOrderDto.Good(
        good.id().toString(),
        new ExchangeItemRefDto(
            good.inputMaterialId().toString(),
            ExchangeStockFeedService.cut(good.inputMaterialName())),
        good.inputQuantity(),
        new ExchangeItemRefDto(
            good.outputMaterialId().toString(),
            ExchangeStockFeedService.cut(good.outputMaterialName())),
        good.outputQuantity(),
        good.quality());
  }

  /**
   * What one page's orders name by id, read once per page.
   *
   * @param guids the starmap GUID by location id
   * @param clientNames the display name by client id
   * @param clientKeys the caller's own key by id of an order it still names
   * @param removedKeys the caller's own key by id of every order, for a tombstone; a key a later
   *     create took over still names the removed order
   */
  private record References(
      @NotNull Map<UUID, UUID> guids,
      @NotNull Map<String, String> clientNames,
      @NotNull Map<String, String> clientKeys,
      @NotNull Map<String, String> removedKeys) {}
}
