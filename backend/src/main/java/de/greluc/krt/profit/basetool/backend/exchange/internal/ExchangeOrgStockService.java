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
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockEntryDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockLotDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockPoolDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgStockUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeQuantityDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockLotDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeOrgStockEntryRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeOrgStockLotRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
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
 * The org stock as a snapshot and a change feed for exchange clients: the shared lots of the units
 * the member directly belongs to, one pool per unit (REQ-XCH-042, ADR-0257), with the single
 * entries in the pools of the member's Logistician units when the client holds the details
 * capability (REQ-XCH-043).
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
  private final ExchangeNameWithholdings nameWithholdings;
  private final ExchangeHolderRefs holderRefs;
  private final MeterRegistry meterRegistry;

  /**
   * The pools one request reads.
   *
   * @param byId the units the member reads, by id
   * @param units the same units in their listing order, each with the member's Logistician flag
   * @param detailed the units among them whose lots carry their entries
   */
  private record Pools(
      @NotNull Map<UUID, ExchangeOrgUnitDto> byId,
      @NotNull List<ExchangeOrgStockPoolDto> units,
      @NotNull Set<UUID> detailed) {}

  /**
   * Returns one snapshot or feed page of the org stock and counts it for the caller's client.
   *
   * @param caller the member the current request acts for and their client
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @param details whether the client holds {@code exchange.orgstock.details}
   * @return the page with the units the member reads
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgStockPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit, boolean details) {
    int size = ExchangeFeedReader.pageSize(limit);
    List<ExchangeOrgUnitDto> units = orgUnitService.orgUnits(caller.member()).items();
    Map<UUID, ExchangeOrgUnitDto> byId =
        units.stream().collect(Collectors.toMap(ExchangeOrgUnitDto::id, Function.identity()));
    Set<UUID> logistician = new HashSet<>(orgUnitService.logisticianUnits(caller.member()));
    logistician.retainAll(byId.keySet());
    List<ExchangeOrgStockPoolDto> listed =
        units.stream()
            .map(unit -> ExchangeOrgStockPoolDto.of(unit, logistician.contains(unit.id())))
            .toList();
    Pools pools = new Pools(byId, listed, details ? logistician : Set.of());
    ExchangeOrgStockPageDto page;
    if (cursor == null) {
      page = snapshot(caller, pools, feedReader.snapshotStart(), FIRST, size);
    } else {
      ExchangeFeedCursor position = feedReader.resume(cursor);
      UUID afterId = position.afterId();
      page =
          afterId != null
              ? snapshot(caller, pools, position.position(), afterId, size)
              : feed(caller, pools, position.position(), size);
    }
    meterRegistry
        .counter(MetricNames.EXCHANGE_ORG_STOCK_PAGES, MetricNames.TAG_CLIENT_ID, caller.clientId())
        .increment();
    return page;
  }

  /**
   * Reads one snapshot page.
   *
   * @param caller the member and client the page is read for
   * @param pools the pools the member reads
   * @param at the feed position the snapshot was taken at
   * @param afterId the lowest row id of the last lot delivered
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeOrgStockPageDto snapshot(
      @NotNull ExchangeCaller caller,
      @NotNull Pools pools,
      @NotNull ExchangeFeedPosition at,
      @NotNull UUID afterId,
      int size) {
    List<ExchangeOrgStockLotRow> rows =
        stockReads.exchangeOrgLots(pools.byId().keySet(), afterId, size + 1);
    boolean more = rows.size() > size;
    List<ExchangeOrgStockLotRow> delivered = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? ExchangeFeedCursor.snapshot(at, delivered.getLast().getAnchor()).format()
            : ExchangeFeedCursor.feed(at).format();
    return new ExchangeOrgStockPageDto(
        toDtos(caller, pools, delivered), List.of(), pools.units(), next, more);
  }

  /**
   * Reads one feed page: each lot of the member's current pools changed after the position, once,
   * with its current state or a tombstone when it holds no shared rows any more.
   *
   * @param caller the member and client the page is read for
   * @param pools the pools the member reads
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeOrgStockPageDto feed(
      @NotNull ExchangeCaller caller,
      @NotNull Pools pools,
      @NotNull ExchangeFeedPosition after,
      int size) {
    ExchangeFeedReader.Changes changes =
        feedReader.orgChanges(caller.member(), pools.byId().keySet(), after, size);
    List<ExchangeOrgStockLotRow> current =
        stockReads.exchangeOrgLotsByKeys(pools.byId().keySet(), changes.keys());
    Map<String, ExchangeOrgStockLotDto> byKey =
        toDtos(caller, pools, current).stream()
            .collect(Collectors.toMap(ExchangeOrgStockLotDto::key, Function.identity()));
    List<ExchangeOrgStockLotDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      ExchangeOrgStockLotDto lot = byKey.get(key);
      if (lot != null) {
        items.add(lot);
      } else {
        removed.add(changes.tombstone(key, key));
      }
    }
    return new ExchangeOrgStockPageDto(
        items, removed, pools.units(), changes.nextCursor(), changes.more());
  }

  /**
   * Builds the page entries of the given lots, with the single entries of the lots in the detailed
   * pools read in one pass.
   *
   * @param caller the member and client the lots are read for
   * @param pools the pools the member reads
   * @param lots the summed rows of the lots
   * @return the page entries in the order of {@code lots}
   */
  private @NotNull List<ExchangeOrgStockLotDto> toDtos(
      @NotNull ExchangeCaller caller,
      @NotNull Pools pools,
      @NotNull List<ExchangeOrgStockLotRow> lots) {
    List<String> detailedKeys =
        lots.stream()
            .filter(lot -> pools.detailed().contains(lot.getUnitId()))
            .map(ExchangeOrgStockLotRow::getLotKey)
            .toList();
    Map<String, List<ExchangeOrgStockEntryRow>> entries =
        stockReads.exchangeOrgEntries(pools.detailed(), detailedKeys).stream()
            .collect(Collectors.groupingBy(ExchangeOrgStockEntryRow::lotKey));
    Set<UUID> withheld =
        nameWithholdings.withholding(
            entries.values().stream()
                .flatMap(List::stream)
                .map(ExchangeOrgStockEntryRow::holderId)
                .collect(Collectors.toSet()));
    Function<UUID, String> refs =
        entries.isEmpty() ? _ -> "" : holderRefs.forClient(caller.clientId());
    return lots.stream()
        .map(
            lot ->
                toDto(
                    lot,
                    Objects.requireNonNull(pools.byId().get(lot.getUnitId())),
                    pools.detailed().contains(lot.getUnitId())
                        ? entries.getOrDefault(lot.getLotKey(), List.of())
                        : null,
                    withheld,
                    refs))
        .toList();
  }

  /**
   * Builds an org-stock lot from the stock lot fields of its row, its unit's id and name, its
   * earmarks in the lot's own quantity unit and, in a detailed pool, its entries.
   *
   * @param lot the summed row of one pool's lot
   * @param unit the unit whose pool holds it
   * @param entries the lot's shared rows, or {@code null} outside a detailed pool
   * @param withheld the holders who withhold their name
   * @param refs the client's holder pseudonyms
   * @return the page entry
   */
  private static @NotNull ExchangeOrgStockLotDto toDto(
      @NotNull ExchangeOrgStockLotRow lot,
      @NotNull ExchangeOrgUnitDto unit,
      @Nullable List<ExchangeOrgStockEntryRow> entries,
      @NotNull Set<UUID> withheld,
      @NotNull Function<UUID, String> refs) {
    ExchangeStockLotDto base = ExchangeStockFeedService.toDto(lot);
    String quantityUnit = base.quantity().unit();
    return new ExchangeOrgStockLotDto(
        lot.getLotKey(),
        new ExchangeOrgStockUnitDto(unit.id(), unit.name()),
        base.material(),
        base.materialKind(),
        base.component(),
        base.location(),
        base.quality(),
        base.stolen(),
        base.quantity(),
        new ExchangeEarmarkedDto(
            quantity(lot.getJobOrderEarmarked(), quantityUnit),
            quantity(lot.getMissionEarmarked(), quantityUnit)),
        entries == null
            ? null
            : entries.stream().map(entry -> toEntry(entry, quantityUnit, withheld, refs)).toList());
  }

  /**
   * Builds one entry; a holder who withholds their name is shown without name, ref or note.
   *
   * @param entry the shared row
   * @param quantityUnit the lot's quantity unit
   * @param withheld the holders who withhold their name
   * @param refs the client's holder pseudonyms
   * @return the entry
   */
  private static @NotNull ExchangeOrgStockEntryDto toEntry(
      @NotNull ExchangeOrgStockEntryRow entry,
      @NotNull String quantityUnit,
      @NotNull Set<UUID> withheld,
      @NotNull Function<UUID, String> refs) {
    boolean hidden = withheld.contains(entry.holderId());
    double jobOrders = 0;
    List<ExchangeOrgStockEntryDto.JobOrderEarmark> jobOrderEarmarks = new ArrayList<>();
    for (ExchangeOrgStockEntryRow.JobOrder slice : entry.jobOrders()) {
      jobOrders += slice.amount();
      jobOrderEarmarks.add(
          new ExchangeOrgStockEntryDto.JobOrderEarmark(
              slice.jobOrderId().toString(),
              slice.displayId(),
              quantity(slice.amount(), quantityUnit),
              slice.delivered()));
    }
    double missions = 0;
    List<ExchangeOrgStockEntryDto.MissionEarmark> missionEarmarks = new ArrayList<>();
    for (ExchangeOrgStockEntryRow.Mission slice : entry.missions()) {
      missions += slice.amount();
      missionEarmarks.add(
          new ExchangeOrgStockEntryDto.MissionEarmark(
              slice.missionId().toString(),
              slice.name() == null ? null : ExchangeStockFeedService.cut(slice.name()),
              quantity(slice.amount(), quantityUnit)));
    }
    return new ExchangeOrgStockEntryDto(
        entry.id().toString(),
        entry.version(),
        hidden
            ? ExchangeOrgStockEntryDto.Holder.WITHHELD
            : new ExchangeOrgStockEntryDto.Holder(
                false,
                ExchangeStockFeedService.cut(entry.holderName()),
                refs.apply(entry.holderId())),
        quantity(entry.amount(), quantityUnit),
        hidden ? null : entry.note(),
        List.copyOf(jobOrderEarmarks),
        List.copyOf(missionEarmarks),
        new ExchangeEarmarkedDto(
            quantity(Math.max(0, entry.amount() - jobOrders), quantityUnit),
            quantity(Math.max(0, entry.amount() - missions), quantityUnit)));
  }

  /**
   * Builds a quantity in the lot's unit.
   *
   * @param amount the amount
   * @param quantityUnit the lot's quantity unit
   * @return the quantity, rounded as the lot's amounts are
   */
  private static @NotNull ExchangeQuantityDto quantity(
      double amount, @NotNull String quantityUnit) {
    return new ExchangeQuantityDto(
        ExchangeStockFeedService.amount(amount, quantityUnit), quantityUnit);
  }
}
