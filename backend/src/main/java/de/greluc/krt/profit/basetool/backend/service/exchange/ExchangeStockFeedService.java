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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeMaterialKindDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeQuantityDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockLotDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockPageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository.ExchangeStockLotRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's stock lots as a snapshot and a change feed for exchange clients (REQ-XCH-013,
 * REQ-XCH-016, ADR-0224, ADR-0230).
 *
 * <p>A lot is the member's rows, personal and shared, of one material or item at one location,
 * quality and stolen state, summed across org-unit pools; its key is the one the change feed
 * records. A snapshot pages lots by their lowest row id: a lot whose rows change while the snapshot
 * is read may move, but every such change follows in the feed.
 */
@Service
@RequiredArgsConstructor
public class ExchangeStockFeedService {

  /** The id before every row, which starts a snapshot. */
  private static final UUID FIRST = new UUID(0, 0);

  /** The longest display name the published item and location references carry. */
  private static final int MAX_NAME = 200;

  /** The decimals an SCU amount carries. */
  private static final int SCU_SCALE = 3;

  private final InventoryItemRepository inventoryRepository;
  private final ExchangeFeedReader feedReader;

  /**
   * Returns one page of the member's stock lots.
   *
   * @param member the member
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeStockPageDto page(
      @NotNull UUID member, @Nullable String cursor, int limit) {
    int size = ExchangeFeedReader.pageSize(limit);
    if (cursor == null) {
      return snapshot(member, feedReader.snapshotStart(), FIRST, size);
    }
    ExchangeFeedCursor position = feedReader.resume(cursor);
    UUID afterId = position.afterId();
    return afterId != null
        ? snapshot(member, position.position(), afterId, size)
        : feed(member, position.position(), size);
  }

  /**
   * Reads one snapshot page.
   *
   * @param member the member
   * @param at the feed position the snapshot was taken at
   * @param afterId the lowest row id of the last lot delivered
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeStockPageDto snapshot(
      @NotNull UUID member, @NotNull ExchangeFeedPosition at, @NotNull UUID afterId, int size) {
    List<ExchangeStockLotRow> rows =
        inventoryRepository.findExchangeLots(member, afterId, size + 1);
    boolean more = rows.size() > size;
    List<ExchangeStockLotRow> delivered = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? ExchangeFeedCursor.snapshot(at, delivered.getLast().getAnchor()).format()
            : ExchangeFeedCursor.feed(at).format();
    return new ExchangeStockPageDto(
        delivered.stream().map(ExchangeStockFeedService::toDto).toList(), List.of(), next, more);
  }

  /**
   * Reads one feed page: each lot changed after the position, once, with its current state or a
   * tombstone when it holds no rows any more.
   *
   * @param member the member
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeStockPageDto feed(
      @NotNull UUID member, @NotNull ExchangeFeedPosition after, int size) {
    ExchangeFeedReader.Changes changes =
        feedReader.changes(member, ExchangeResource.STOCK, after, size);
    Map<String, ExchangeStockLotRow> current =
        changes.keys().isEmpty()
            ? Map.of()
            : inventoryRepository.findExchangeLotsByKeys(member, changes.keys()).stream()
                .collect(Collectors.toMap(ExchangeStockLotRow::getLotKey, Function.identity()));
    List<ExchangeStockLotDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      ExchangeStockLotRow lot = current.get(key);
      if (lot != null) {
        items.add(toDto(lot));
      } else {
        removed.add(changes.tombstone(key, key));
      }
    }
    return new ExchangeStockPageDto(items, removed, changes.nextCursor(), changes.more());
  }

  /**
   * Maps a lot.
   *
   * @param lot the lot
   * @return the feed entry
   */
  private static @NotNull ExchangeStockLotDto toDto(@NotNull ExchangeStockLotRow lot) {
    boolean material = lot.getMaterialId() != null;
    ExchangeItemRefDto ref =
        material
            ? new ExchangeItemRefDto(lot.getMaterialId().toString(), cut(lot.getMaterialName()))
            : new ExchangeItemRefDto(lot.getGameItemId().toString(), cut(lot.getGameItemName()));
    ExchangeMaterialKindDto kind =
        material
            ? new ExchangeMaterialKindDto(
                lot.getMaterialType(),
                Boolean.TRUE.equals(lot.getCommodity()),
                flag(lot.getMineral()),
                flag(lot.getHarvestable()),
                flag(lot.getRaw()),
                flag(lot.getRefined()),
                flag(lot.getBuyable()),
                flag(lot.getSellable()))
            : null;
    String unit =
        material && QuantityType.SCU.name().equals(lot.getQuantityType())
            ? QuantityType.SCU.name()
            : QuantityType.PIECE.name();
    return new ExchangeStockLotDto(
        lot.getLotKey(),
        ref,
        kind,
        new ExchangeLocationDto(cut(lot.getLocationName()), uex(lot)),
        lot.getQuality(),
        lot.getStolen(),
        new ExchangeQuantityDto(amount(lot.getAmount(), unit), unit));
  }

  /**
   * Reads a UEX {@code 0}/{@code 1} flag.
   *
   * @param value the stored flag
   * @return {@code true} for {@code 1}, {@code false} for any other value, {@code null} when
   *     unknown
   */
  static @Nullable Boolean flag(@Nullable Integer value) {
    return value == null ? null : value == 1;
  }

  /**
   * Returns the UEX place of a lot's location; a city link wins over a space-station link.
   *
   * @param lot the lot
   * @return the place, or {@code null} when the location links none
   */
  private static @Nullable ExchangeLocationDto.UexRef uex(@NotNull ExchangeStockLotRow lot) {
    if (lot.getUexCityId() != null && lot.getUexCityId() > 0) {
      return new ExchangeLocationDto.UexRef(ExchangeCatalogService.KIND_CITY, lot.getUexCityId());
    }
    if (lot.getUexSpaceStationId() != null && lot.getUexSpaceStationId() > 0) {
      return new ExchangeLocationDto.UexRef(
          ExchangeCatalogService.KIND_SPACE_STATION, lot.getUexSpaceStationId());
    }
    return null;
  }

  /**
   * Rounds an amount to its unit: three decimals for SCU, whole pieces otherwise.
   *
   * @param amount the summed amount
   * @param unit the unit
   * @return the amount in plain notation
   */
  static @NotNull BigDecimal amount(double amount, @NotNull String unit) {
    int scale = QuantityType.SCU.name().equals(unit) ? SCU_SCALE : 0;
    BigDecimal rounded =
        BigDecimal.valueOf(amount).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    return rounded.scale() < 0 ? rounded.setScale(0, RoundingMode.UNNECESSARY) : rounded;
  }

  /**
   * Cuts a display name to the published limit.
   *
   * @param name the name
   * @return the name, at most {@value #MAX_NAME} characters
   */
  private static @NotNull String cut(@NotNull String name) {
    return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
  }
}
