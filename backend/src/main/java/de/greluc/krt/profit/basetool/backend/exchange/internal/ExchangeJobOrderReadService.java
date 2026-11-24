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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader.DatedChange;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeFeedReader.DatedChanges;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderFingerprintRepository.ClientSetRow;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderPageDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet.RelatingMember;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's job-order set for exchange clients (REQ-OXCH-002, REQ-OXCH-003): a snapshot of the
 * orders of the read set that are active, closed within the window or aUEC with an open settlement,
 * paged by id, whose last page hands over to the feed at the snapshot's position; and the feed of
 * the orders announced since, each resolved against the member's current set into an item or a
 * tombstone without a writer.
 */
@Service
@RequiredArgsConstructor
public class ExchangeJobOrderReadService {

  private final ExchangeFeedReader feedReader;
  private final ExchangeJobOrderFingerprintRepository fingerprints;
  private final JobOrderExchangeReadSet readSet;
  private final ExchangeJobOrderShaper shaper;
  private final ExchangeJobOrderMetrics metrics;
  private final Clock clock = Clock.systemUTC();

  /**
   * Returns one snapshot or feed page of the member's job-order set, read from one database state.
   *
   * @param caller the acting member and client
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor the server did not
   *     issue or one older than the retained changes
   */
  @NotNull
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ExchangeJobOrderPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    return feedReader.answer(
        cursor,
        limit,
        (at, afterId, size) -> snapshot(caller, at, afterId, size),
        (after, size) -> feed(caller, after, size));
  }

  private ExchangeJobOrderPageDto snapshot(
      ExchangeCaller caller, ExchangeFeedPosition at, UUID afterId, int size) {
    RelatingMember member = readSet.relatingMember(caller.member());
    List<ClientSetRow> rows =
        fingerprints.findClientSetAfter(
            member.unitIds(), member.memberId(), afterId, closedSince(), Limit.of(size + 1));
    ExchangeFeedReader.Slice<ClientSetRow> slice =
        ExchangeFeedReader.slice(rows, size, at, ClientSetRow::orderId);
    Map<UUID, Instant> updatedAt = new LinkedHashMap<>();
    slice.items().forEach(row -> updatedAt.put(row.orderId(), row.updatedAt()));
    metrics.countRead(caller.clientId(), JobOrderExchangeReadRoute.SNAPSHOT);
    return new ExchangeJobOrderPageDto(
        shaper.shape(caller, member, updatedAt), List.of(), slice.next(), slice.more());
  }

  private ExchangeJobOrderPageDto feed(
      ExchangeCaller caller, ExchangeFeedPosition after, int size) {
    RelatingMember member = readSet.relatingMember(caller.member());
    DatedChanges changes =
        feedReader.jobOrderChanges(member.memberId(), member.unitIds(), after, size);
    Map<UUID, Instant> announced = new LinkedHashMap<>();
    for (DatedChange change : changes.changes()) {
      announced.put(UUID.fromString(change.key()), change.at());
    }
    Map<UUID, Instant> inSet = clientSet(member, announced.keySet());
    Map<UUID, Instant> updatedAt = new LinkedHashMap<>();
    for (UUID order : announced.keySet()) {
      Instant fingerprinted = inSet.get(order);
      if (fingerprinted != null) {
        updatedAt.put(order, fingerprinted);
      }
    }
    List<ExchangeJobOrderDto> items = shaper.shape(caller, member, updatedAt);
    Set<UUID> delivered =
        items.stream().map(ExchangeJobOrderDto::id).collect(Collectors.toUnmodifiableSet());
    List<ExchangeJobOrderPageDto.Removed> removed =
        announced.entrySet().stream()
            .filter(entry -> !delivered.contains(entry.getKey()))
            .map(entry ->
                new ExchangeJobOrderPageDto.Removed(entry.getKey().toString(), entry.getValue()))
            .toList();
    metrics.countRead(caller.clientId(), JobOrderExchangeReadRoute.FEED);
    return new ExchangeJobOrderPageDto(items, removed, changes.nextCursor(), changes.more());
  }

  private Map<UUID, Instant> clientSet(RelatingMember member, Set<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    return fingerprints
        .findClientSetAmong(member.unitIds(), member.memberId(), orderIds, closedSince())
        .stream()
        .collect(Collectors.toMap(ClientSetRow::orderId, ClientSetRow::updatedAt));
  }

  private Instant closedSince() {
    return JobOrderClientWindow.closedSince(clock.instant());
  }
}
