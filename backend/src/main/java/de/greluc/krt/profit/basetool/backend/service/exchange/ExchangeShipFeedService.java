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

import de.greluc.krt.profit.basetool.backend.exception.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.ExchangeShipLink;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeInsuranceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeShipPageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeTombstoneDto;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeShipLinkRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository.ExchangeShipRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own ships as a snapshot and a change feed for exchange clients (REQ-XCH-013,
 * REQ-XCH-017, ADR-0224).
 *
 * <p>A snapshot pages through the member's ships by id and ends with the feed position it was taken
 * at; the feed answers each ship changed after the cursor once, with its current state or a
 * tombstone. Purchase data is never read.
 */
@Service
@RequiredArgsConstructor
public class ExchangeShipFeedService {

  /** The id before every ship, which starts a snapshot. */
  private static final UUID FIRST = new UUID(0, 0);

  /** The insurance value of a lifetime insurance. */
  private static final String LTI = "LTI";

  /** The insurance kind of a number of months. */
  private static final String MONTHS = "MONTHS";

  /** The longest display name the published item and location references carry. */
  private static final int MAX_NAME = 200;

  private final ShipRepository shipRepository;
  private final ExchangeShipLinkRepository linkRepository;
  private final ExchangeFeedReader feedReader;

  /**
   * Returns one page of the member's ships, each with the id the calling installation linked it to.
   *
   * @param caller the client, installation and member
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeShipPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    int size = ExchangeFeedReader.pageSize(limit);
    if (cursor == null) {
      return snapshot(caller, feedReader.snapshotStart(), FIRST, size);
    }
    ExchangeFeedCursor position = feedReader.resume(cursor);
    UUID afterId = position.afterId();
    return afterId != null
        ? snapshot(caller, position.position(), afterId, size)
        : feed(caller, position.position(), size);
  }

  /**
   * Reads one snapshot page.
   *
   * @param caller the caller
   * @param at the feed position the snapshot was taken at
   * @param afterId the last ship delivered
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeShipPageDto snapshot(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeFeedPosition at,
      @NotNull UUID afterId,
      int size) {
    List<ExchangeShipRow> rows =
        shipRepository.findExchangeShips(caller.member(), afterId, PageRequest.of(0, size + 1));
    boolean more = rows.size() > size;
    List<ExchangeShipRow> delivered = more ? rows.subList(0, size) : rows;
    String next =
        more
            ? ExchangeFeedCursor.snapshot(at, delivered.getLast().id()).format()
            : ExchangeFeedCursor.feed(at).format();
    Map<UUID, String> linked = links(caller, delivered);
    return new ExchangeShipPageDto(
        delivered.stream().map(ship -> toDto(ship, linked.get(ship.id()))).toList(),
        List.of(),
        next,
        more);
  }

  /**
   * Reads one feed page: each ship changed after the position, once, with its current state or a
   * tombstone.
   *
   * @param caller the caller
   * @param after the position
   * @param size the page size
   * @return the page
   */
  private @NotNull ExchangeShipPageDto feed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeFeedPosition after, int size) {
    UUID member = caller.member();
    ExchangeFeedReader.Changes changes =
        feedReader.changes(member, ExchangeResource.SHIP, after, size);
    Map<String, ExchangeShipRow> current =
        changes.keys().isEmpty()
            ? Map.of()
            : shipRepository
                .findExchangeShipsByIds(
                    member, changes.keys().stream().map(UUID::fromString).toList())
                .stream()
                .collect(Collectors.toMap(row -> row.id().toString(), Function.identity()));
    Map<UUID, String> linked = links(caller, current.values());
    List<ExchangeShipDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      ExchangeShipRow ship = current.get(key);
      if (ship != null) {
        items.add(toDto(ship, linked.get(ship.id())));
      } else {
        removed.add(changes.tombstone(key, key));
      }
    }
    return new ExchangeShipPageDto(items, removed, changes.nextCursor(), changes.more());
  }

  /**
   * Reads the calling installation's ids of the given ships.
   *
   * @param caller the caller
   * @param ships the ships
   * @return the linked id by ship
   */
  private @NotNull Map<UUID, String> links(
      @NotNull ExchangeCaller caller, @NotNull Collection<ExchangeShipRow> ships) {
    if (ships.isEmpty()) {
      return Map.of();
    }
    return linkRepository
        .findByUserIdAndClientIdAndInstallationKeyAndShipIdIn(
            caller.member(),
            caller.clientId(),
            caller.installationKey(),
            ships.stream().map(ExchangeShipRow::id).toList())
        .stream()
        .collect(Collectors.toMap(ExchangeShipLink::getShipId, ExchangeShipLink::getExternalId));
  }

  /**
   * Maps a ship.
   *
   * @param ship the ship
   * @param externalId the calling installation's id for it, or {@code null}
   * @return the feed entry
   */
  private static @NotNull ExchangeShipDto toDto(
      @NotNull ExchangeShipRow ship, @Nullable String externalId) {
    return new ExchangeShipDto(
        ship.id().toString(),
        externalId,
        ship.version() == null ? 0 : ship.version(),
        new ExchangeItemRefDto(ship.shipTypeId().toString(), cut(ship.shipTypeName())),
        ship.name() == null || ship.name().isBlank() ? null : ship.name(),
        insurance(ship.insurance()),
        ship.locationName() == null
            ? null
            : new ExchangeLocationDto(cut(ship.locationName()), uex(ship)),
        ship.fitted());
  }

  /**
   * Reads the stored insurance.
   *
   * @param insurance {@code LTI}, the months as digits, or {@code null}
   * @return lifetime, or the months; a ship stored without insurance reads as zero months
   */
  static @NotNull ExchangeInsuranceDto insurance(@Nullable String insurance) {
    if (LTI.equals(insurance)) {
      return new ExchangeInsuranceDto(LTI, null);
    }
    if (insurance != null && insurance.matches("^\\d{1,3}$")) {
      return new ExchangeInsuranceDto(MONTHS, Math.min(Integer.parseInt(insurance), 120));
    }
    return new ExchangeInsuranceDto(MONTHS, 0);
  }

  /**
   * Returns the UEX place of a ship's location; a city link wins over a space-station link.
   *
   * @param ship the ship
   * @return the place, or {@code null} when the location links none
   */
  private static @Nullable ExchangeLocationDto.UexRef uex(@NotNull ExchangeShipRow ship) {
    if (ship.uexCityId() != null && ship.uexCityId() > 0) {
      return new ExchangeLocationDto.UexRef(ExchangeCatalogService.KIND_CITY, ship.uexCityId());
    }
    if (ship.uexSpaceStationId() != null && ship.uexSpaceStationId() > 0) {
      return new ExchangeLocationDto.UexRef(
          ExchangeCatalogService.KIND_SPACE_STATION, ship.uexSpaceStationId());
    }
    return null;
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
