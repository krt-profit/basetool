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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJobOrderFingerprintRepository.ClientSetRow;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderPageDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReadSet.RelatingMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's job-order set as a snapshot for exchange clients (REQ-OXCH-002, REQ-OXCH-003): the
 * orders of the read set that are active, closed within the window or aUEC with an open settlement,
 * paged by id; the last page hands over to the feed at the snapshot's position. A resumed feed
 * cursor answers {@code 410 CURSOR_EXPIRED} until the feed is served, so a client takes a new
 * snapshot.
 */
@Service
@RequiredArgsConstructor
public class ExchangeJobOrderReadService {

  /** How long a terminal order stays in the client set after closing. */
  static final Duration CLOSED_WINDOW = Duration.ofDays(7);

  private final ExchangeFeedReader feedReader;
  private final ExchangeJobOrderFingerprintRepository fingerprints;
  private final JobOrderExchangeReadSet readSet;
  private final ExchangeJobOrderShaper shaper;
  private final ExchangeJobOrderMetrics metrics;
  private final Clock clock = Clock.systemUTC();

  /**
   * Returns one snapshot page of the member's job-order set, read from one database state.
   *
   * @param caller the acting member and client
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a feed cursor, a cursor the
   *     server did not issue or one older than the retained changes
   */
  @NotNull
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ExchangeJobOrderPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    ExchangeJobOrderPageDto page =
        feedReader.answer(
            cursor,
            limit,
            (at, afterId, size) -> snapshot(caller, at, afterId, size),
            (_, _) -> {
              throw ExchangeProblemException.cursorExpired();
            });
    metrics.countRead(caller.clientId(), JobOrderExchangeReadRoute.SNAPSHOT);
    return page;
  }

  private ExchangeJobOrderPageDto snapshot(
      ExchangeCaller caller, ExchangeFeedPosition at, UUID afterId, int size) {
    RelatingMember member = readSet.relatingMember(caller.member());
    List<ClientSetRow> rows =
        fingerprints.findClientSetAfter(
            member.unitIds(),
            member.memberId(),
            afterId,
            clock.instant().minus(CLOSED_WINDOW),
            Limit.of(size + 1));
    ExchangeFeedReader.Slice<ClientSetRow> slice =
        ExchangeFeedReader.slice(rows, size, at, ClientSetRow::orderId);
    Map<UUID, Instant> updatedAt = new LinkedHashMap<>();
    slice.items().forEach(row -> updatedAt.put(row.orderId(), row.updatedAt()));
    return new ExchangeJobOrderPageDto(
        shaper.shape(caller, member, updatedAt), List.of(), slice.next(), slice.more());
  }
}
