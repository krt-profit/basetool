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

import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestAccountDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestTargetDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeTombstoneDto;
import java.math.BigDecimal;
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
 * The member's own booking requests as a snapshot and a change feed for exchange clients
 * (REQ-BXCH-003, REQ-XCH-013, ADR-0224). Every request the member raised counts, whichever channel
 * raised it; the feed's keys are request ids, written by the {@code bank_booking_request} triggers.
 * Every page served is counted (REQ-OBS-024).
 */
@Service
@RequiredArgsConstructor
public class ExchangeBankRequestService {

  private final BankOwnRequestReads requestReads;
  private final ExchangeFeedReader feedReader;
  private final ExchangeBankMetrics metrics;

  /**
   * Returns one page of the member's own requests.
   *
   * @param caller the acting member and client
   * @param cursor the cursor the client echoed, or {@code null} for a new snapshot
   * @param limit the page size, clamped to {@code 1..}{@value ExchangeFeedReader#MAX_LIMIT}
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor older than the
   *     retained changes or not issued by the server
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeBankRequestPageDto page(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    int size = ExchangeFeedReader.pageSize(limit);
    UUID member = caller.member();
    ExchangeBankRequestPageDto page;
    if (cursor == null) {
      page = snapshot(member, feedReader.snapshotStart(), null, size);
    } else {
      ExchangeFeedCursor position = feedReader.resume(cursor);
      page =
          position.isSnapshot()
              ? snapshot(member, position.position(), position.afterId(), size)
              : feed(member, position.position(), size);
    }
    metrics.countRead(caller.clientId(), BankExchangeReadRoute.REQUESTS);
    return page;
  }

  /**
   * Reads one snapshot page; its last page hands over to the feed at the snapshot's position.
   *
   * @param member the member
   * @param at the feed position the snapshot was taken at
   * @param afterId the last request delivered, or {@code null} for the first page
   * @param size the page size
   * @return the page
   */
  @NotNull
  private ExchangeBankRequestPageDto snapshot(
      @NotNull UUID member, @NotNull ExchangeFeedPosition at, @Nullable UUID afterId, int size) {
    BankOwnRequestReads.OwnRequestPage rows = requestReads.ownRequests(member, afterId, size);
    String next =
        rows.hasMore()
            ? ExchangeFeedCursor.snapshot(at, rows.items().getLast().id()).format()
            : ExchangeFeedCursor.feed(at).format();
    return new ExchangeBankRequestPageDto(
        rows.items().stream().map(ExchangeBankRequestService::toDto).toList(),
        List.of(),
        next,
        rows.hasMore());
  }

  /**
   * Reads one feed page: each request changed after the position, once, with its current state or a
   * tombstone.
   *
   * @param member the member
   * @param after the position
   * @param size the page size
   * @return the page
   */
  @NotNull
  private ExchangeBankRequestPageDto feed(
      @NotNull UUID member, @NotNull ExchangeFeedPosition after, int size) {
    ExchangeFeedReader.Changes changes =
        feedReader.changes(member, ExchangeResource.BANK_REQUEST, after, size);
    Map<String, BankOwnRequestReads.OwnRequest> current =
        requestReads
            .ownRequestsByIds(member, changes.keys().stream().map(UUID::fromString).toList())
            .stream()
            .collect(Collectors.toMap(r -> r.id().toString(), Function.identity()));
    List<ExchangeBankRequestDto> items = new ArrayList<>();
    List<ExchangeTombstoneDto> removed = new ArrayList<>();
    for (String key : changes.keys()) {
      BankOwnRequestReads.OwnRequest request = current.get(key);
      if (request != null) {
        items.add(toDto(request));
      } else {
        removed.add(changes.tombstone(key, key));
      }
    }
    return new ExchangeBankRequestPageDto(items, removed, changes.nextCursor(), changes.more());
  }

  /**
   * Shapes one request; money and the split percent are whole JSON integers.
   *
   * @param request the request read
   * @return the allow-listed request
   */
  @NotNull
  private static ExchangeBankRequestDto toDto(@NotNull BankOwnRequestReads.OwnRequest request) {
    BankOwnRequestReads.Account account = request.account();
    BankOwnRequestReads.TargetAccount target = request.targetAccount();
    BankRequestApprover approver = request.requiredApprover();
    BigDecimal split = request.splitPercent();
    return new ExchangeBankRequestDto(
        request.id(),
        request.version(),
        request.status(),
        request.createdAt(),
        request.decidedAt(),
        request.type().name(),
        request.amount().longValueExact(),
        request.note(),
        request.justification(),
        new ExchangeBankRequestAccountDto(
            account.id(), account.no(), account.name(), account.orgUnitShorthand()),
        target == null ? null : new ExchangeBankRequestTargetDto(target.id(), target.no()),
        request.requiresOwnerApproval(),
        approver == null ? null : approver.name(),
        request.ownerApprovalGranted(),
        request.rejectReason(),
        null,
        request.splitEnabled(),
        split == null ? null : split.intValueExact());
  }
}
