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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeEarmarkedDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockLotDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeQuantityDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockLotDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeOrgStockLotRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org stock as a snapshot and a change feed for exchange clients: the shared lots of the units
 * the member directly belongs to, one pool per unit (REQ-XCH-042, ADR-0257).
 *
 * <p>A snapshot pages lots by their lowest row id, like the member's own stock, and its last page
 * carries the feed cursor of the position it was taken at. The feed is keyed by unit and reads only
 * the pools the member belongs to at the time of the request; every page lists those units.
 */
@Service
@RequiredArgsConstructor
public class ExchangeOrgStockService {

  /** The id before every row, which starts a snapshot. */
  private static final UUID FIRST = new UUID(0, 0);

  private final StockReads stockReads;
  private final ExchangeFeedReader feedReader;
  private final ExchangeOrgUnitService orgUnitService;
  private final MeterRegistry meterRegistry;

  /**
   * Returns one snapshot or feed page of the org stock and counts it for the caller's client.
   *
   * @param caller the member the current request acts for and their client
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page with the units the member reads
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgStockPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    int size = ExchangeFeedReader.pageSize(limit);
    List<ExchangeOrgUnitDto> units = orgUnitService.orgUnits(caller.member()).items();
    Map<UUID, ExchangeOrgUnitDto> byId =
        units.stream().collect(Collectors.toMap(ExchangeOrgUnitDto::id, Function.identity()));
    ExchangeOrgStockPageDto page;
    if (cursor == null) {
      page = snapshot(byId, units, feedReader.snapshotStart(), FIRST, size);
    } else {
      ExchangeFeedCursor position = feedReader.resume(cursor);
      UUID afterId = position.afterId();
      page =
          afterId != null
              ? snapshot(byId, units, position.position(), afterId, size)
              : feed(caller.member(), byId, units, position.position(), size);
    }
    meterRegistry
        .counter(MetricNames.EXCHANGE_ORG_STOCK_PAGES, MetricNames.TAG_CLIENT_ID, caller.clientId())
        .increment();
    return page;
  }

  /**
   * Reads one snapshot page.
   *
   * @param byId the units the member reads, by id
   * @param units the same units in their listing order
   * @param at the feed position the snapshot was taken at
   * @param afterId the lowest row id of the last lot delivered
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeOrgStockPageDto snapshot(
      @NotNull Map<UUID, ExchangeOrgUnitDto> byId,
      @NotNull List<ExchangeOrgUnitDto> units,
      @NotNull ExchangeFeedPosition at,
      @NotNull UUID afterId,
      int size) {
    List<ExchangeOrgStockLotRow> rows =
        stockReads.exchangeOrgLots(byId.keySet(), afterId, size + 1);
    boolean more = rows.size() > size;
    List<ExchangeOrgStockLotRow> delivered = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? ExchangeFeedCursor.snapshot(at, delivered.getLast().getAnchor()).format()
            : ExchangeFeedCursor.feed(at).format();
    return new ExchangeOrgStockPageDto(
        delivered.stream().map(lot -> toDto(lot, byId)).toList(), List.of(), units, next, more);
  }

  /**
   * Reads one feed page: each lot of the member's current pools changed after the position, once,
   * with its current state or a tombstone when it holds no shared rows any more.
   *
   * @param member the member the client reads for
   * @param byId the units the member reads, by id
   * @param units the same units in their listing order
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeOrgStockPageDto feed(
      @NotNull UUID member,
      @NotNull Map<UUID, ExchangeOrgUnitDto> byId,
      @NotNull List<ExchangeOrgUnitDto> units,
      @NotNull ExchangeFeedPosition after,
      int size) {
    ExchangeFeedReader.Changes changes = feedReader.orgChanges(member, byId.keySet(), after, size);
    Map<String, ExchangeOrgStockLotRow> current =
        stockReads.exchangeOrgLotsByKeys(byId.keySet(), changes.keys()).stream()
            .collect(Collectors.toMap(ExchangeOrgStockLotRow::getLotKey, Function.identity()));
    List<ExchangeOrgStockLotDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      ExchangeOrgStockLotRow lot = current.get(key);
      if (lot != null) {
        items.add(toDto(lot, byId));
      } else {
        removed.add(changes.tombstone(key, key));
      }
    }
    return new ExchangeOrgStockPageDto(items, removed, units, changes.nextCursor(), changes.more());
  }

  /**
   * Builds an org-stock lot for the unit whose pool holds it.
   *
   * @param lot the summed row of one pool's lot
   * @param byId the units the member reads, by id; holds the lot's unit
   * @return the page entry
   */
  private static @NotNull ExchangeOrgStockLotDto toDto(
      @NotNull ExchangeOrgStockLotRow lot, @NotNull Map<UUID, ExchangeOrgUnitDto> byId) {
    return toDto(lot, Objects.requireNonNull(byId.get(lot.getUnitId())));
  }

  /**
   * Builds an org-stock lot from the stock lot fields of its row, its unit's id and name and its
   * earmarks in the lot's own quantity unit.
   *
   * @param lot the summed row of one pool's lot
   * @param unit the unit whose pool holds it
   * @return the page entry
   */
  private static @NotNull ExchangeOrgStockLotDto toDto(
      @NotNull ExchangeOrgStockLotRow lot, @NotNull ExchangeOrgUnitDto unit) {
    ExchangeStockLotDto base = ExchangeStockFeedService.toDto(lot);
    String quantityUnit = base.quantity().unit();
    return new ExchangeOrgStockLotDto(
        lot.getLotKey(),
        new ExchangeOrgStockUnitDto(unit.id(), unit.name()),
        base.material(),
        base.materialKind(),
        base.location(),
        base.quality(),
        base.stolen(),
        base.quantity(),
        new ExchangeEarmarkedDto(
            new ExchangeQuantityDto(
                ExchangeStockFeedService.amount(lot.getJobOrderEarmarked(), quantityUnit),
                quantityUnit),
            new ExchangeQuantityDto(
                ExchangeStockFeedService.amount(lot.getMissionEarmarked(), quantityUnit),
                quantityUnit)));
  }
}
