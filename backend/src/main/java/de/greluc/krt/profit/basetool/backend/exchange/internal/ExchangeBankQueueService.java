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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffQueueReads;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankQueueDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankQueueItemDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only bank-staff queue of the exchange (REQ-BXCH-005): builds the member's actor without
 * the Admin role ({@link BankActors#forExchange}), asks {@link BankStaffQueueReads}, which the bank
 * access policy decides on every call, and shapes the allow-listed answer. A requester who
 * withholds their name loses the handle (REQ-XCH-044). Every snapshot served and every {@code 403
 * NOT_BANK_STAFF} is counted (REQ-OBS-024).
 */
@Service
@RequiredArgsConstructor
public class ExchangeBankQueueService {

  /** The largest page of each section. */
  public static final int MAX_LIMIT = 500;

  /** The age from which the oldest request counts as overdue, the pending-request alert's. */
  static final Duration OVERDUE_AFTER = Duration.ofHours(48);

  /** The Basetool page of one request; the request id follows. */
  static final String LINK_PREFIX = "https://profit-base.online/bank/requests/";

  private final BankActors bankActors;
  private final BankStaffQueueReads queueReads;
  private final ExchangeNameWithholdings nameWithholdings;
  private final ExchangeBankMetrics metrics;
  private final Clock clock = Clock.systemUTC();

  /**
   * One snapshot page of the member's staff queue.
   *
   * @param caller the acting member and client
   * @param cursor the cursor of the previous page, or {@code null}
   * @param limit the requested page size of each section
   * @return the page
   * @throws ExchangeProblemException {@code 403 NOT_BANK_STAFF} for a member without a bank role, a
   *     grant and a request waiting for their approval, {@code 410 CURSOR_EXPIRED} for a cursor the
   *     server did not issue
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeBankQueueDto queue(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    BankExchangeCursor.QueuePosition position =
        cursor == null
            ? new BankExchangeCursor.QueuePosition(null, null)
            : BankExchangeCursor.parseQueue(cursor);
    BankStaffQueueReads.Snapshot snapshot =
        queueReads
            .snapshot(
                bankActors.forExchange(caller.member()),
                position.open(),
                position.toApprove(),
                pageSize(limit))
            .orElseThrow(this::notBankStaff);
    Set<UUID> withheld =
        nameWithholdings.withholding(
            Stream.concat(snapshot.open().items().stream(), snapshot.toApprove().items().stream())
                .map(BankStaffQueueReads.Item::requesterId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    Instant now = clock.instant();
    List<BankStaffQueueReads.Item> open = snapshot.open().items();
    List<BankStaffQueueReads.Item> toApprove = snapshot.toApprove().items();
    boolean hasMore = snapshot.open().hasMore() || snapshot.toApprove().hasMore();
    metrics.countRead(caller.clientId(), BankExchangeReadRoute.QUEUE);
    Instant oldest = snapshot.oldestOpenAt();
    return new ExchangeBankQueueDto(
        oldest,
        oldest != null && Duration.between(oldest, now).compareTo(OVERDUE_AFTER) >= 0,
        open.stream().map(item -> toItem(item, withheld, now)).toList(),
        toApprove.stream().map(item -> toItem(item, withheld, now)).toList(),
        hasMore
            ? BankExchangeCursor.afterQueue(
                last(open, position.open()), last(toApprove, position.toApprove()))
            : null,
        hasMore);
  }

  /**
   * Clamps a requested page size.
   *
   * @param limit the requested size
   * @return the size, from 1 to {@value #MAX_LIMIT}
   */
  static int pageSize(int limit) {
    return Math.clamp(limit, 1, MAX_LIMIT);
  }

  /**
   * The key a section continues after: its last delivered item, or the previous key when the page
   * delivered none.
   *
   * @param items the section's delivered items
   * @param previous the section's key of the previous page, or {@code null}
   * @return the key, or {@code null} for a section still at its start
   */
  private static @Nullable BankStaffQueueReads.QueueKey last(
      @NotNull List<BankStaffQueueReads.Item> items,
      @Nullable BankStaffQueueReads.QueueKey previous) {
    return items.isEmpty() ? previous : items.getLast().key();
  }

  /**
   * Counts and builds the refusal of a member who is no bank staff.
   *
   * @return the refusal
   */
  @NotNull
  private ExchangeProblemException notBankStaff() {
    metrics.countRefused(BankExchangeRefusal.NOT_BANK_STAFF);
    return ExchangeProblemException.notBankStaff();
  }

  /**
   * Shapes one item, without the handle of a requester who withholds their name. The origin client
   * is not recorded on a request, so {@code client} is {@code null}.
   *
   * @param item the queue item
   * @param withheld the requesters of the page who withhold their name
   * @param now the instant the snapshot is taken
   * @return the allow-listed item
   */
  @NotNull
  private static ExchangeBankQueueItemDto toItem(
      @NotNull BankStaffQueueReads.Item item, @NotNull Set<UUID> withheld, @NotNull Instant now) {
    BankStaffQueueReads.AccountRef account = item.account();
    BankStaffQueueReads.TargetRef target = item.target();
    BankRequestApprover approver = item.requiredApprover();
    boolean hidden = item.requesterId() != null && withheld.contains(item.requesterId());
    return new ExchangeBankQueueItemDto(
        item.requestId(),
        item.type().name(),
        ExchangeBankReadService.whole(item.amount()),
        item.createdAt(),
        Math.max(0, Duration.between(item.createdAt(), now).toHours()),
        new ExchangeBankQueueItemDto.Account(
            account.id(), account.no(), account.name(), account.orgUnitShorthand()),
        target == null ? null : new ExchangeBankQueueItemDto.Target(target.id(), target.no()),
        hidden ? null : item.requesterHandle(),
        item.justification(),
        item.note(),
        item.requiresOwnerApproval(),
        approver == null ? null : approver.name(),
        item.ownerApprovalGranted(),
        item.mayConfirm(),
        ExchangeBankReadService.wholeOrNull(item.accountBalance()),
        null,
        LINK_PREFIX + item.requestId());
  }
}
