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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankAccountDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankAccountPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBookingDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankBookingPageDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankCounterAccountDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank reads of the exchange (REQ-BXCH-002): builds the member's actor without the Admin role
 * ({@link BankActors#forExchange}), asks {@link BankAccountReads}, which the bank access policy
 * decides, and shapes the allow-listed answers. A hidden, closed or unknown account answers the
 * same {@code 404 NOT_FOUND}; a counterparty who withholds their name loses the handle
 * (REQ-XCH-044). Every read served and every {@code 404} is counted (REQ-OBS-024).
 */
@Service
@RequiredArgsConstructor
public class ExchangeBankReadService {

  /** The longest period a balance series covers. */
  static final Duration MAX_SERIES_SPAN = Duration.ofDays(365);

  private final BankActors bankActors;
  private final BankAccountReads bankReads;
  private final ExchangeNameWithholdings nameWithholdings;
  private final ExchangeBankMetrics metrics;

  /**
   * One snapshot page of the accounts the member views.
   *
   * @param caller the acting member and client
   * @param cursor the cursor of the previous page, or {@code null}
   * @param limit the requested page size
   * @return the page
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor the server did not
   *     issue or whose account the member no longer views
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeBankAccountPageDto accounts(
      @NotNull ExchangeCaller caller, @Nullable String cursor, int limit) {
    UUID after = cursor == null ? null : BankExchangeCursor.parseAccount(cursor);
    BankAccountReads.AccountPage page =
        bankReads
            .viewableAccounts(actor(caller), after, ExchangeFeedReader.pageSize(limit))
            .orElseThrow(ExchangeProblemException::cursorExpired);
    List<ExchangeBankAccountDto> items =
        page.items().stream().map(ExchangeBankReadService::toAccount).toList();
    metrics.countRead(caller.clientId(), BankExchangeReadRoute.ACCOUNTS);
    return new ExchangeBankAccountPageDto(
        items,
        List.of(),
        page.hasMore() ? BankExchangeCursor.afterAccount(items.getLast().accountId()) : null,
        page.hasMore());
  }

  /**
   * One snapshot page of a viewable account's bookings, newest first.
   *
   * @param caller the acting member and client
   * @param accountId the account
   * @param from inclusive lower bound on the booking instant, or {@code null}
   * @param to inclusive upper bound on the booking instant, or {@code null}
   * @param cursor the cursor of the previous page, or {@code null}
   * @param limit the requested page size
   * @return the page
   * @throws NotFoundException {@code 404 NOT_FOUND} for a hidden, closed or unknown account
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor the server did not
   *     issue
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeBankBookingPageDto bookings(
      @NotNull ExchangeCaller caller,
      @NotNull UUID accountId,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable String cursor,
      int limit) {
    BankAccountReads.BookingKey after =
        cursor == null ? null : BankExchangeCursor.parseBooking(cursor);
    BankAccountReads.BookingPage page =
        bankReads
            .bookings(actor(caller), accountId, from, to, after, ExchangeFeedReader.pageSize(limit))
            .orElseThrow(this::notFound);
    Set<UUID> withheld =
        nameWithholdings.withholding(
            page.items().stream()
                .map(BankAccountReads.Booking::counterpartyUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    metrics.countRead(caller.clientId(), BankExchangeReadRoute.BOOKINGS);
    return new ExchangeBankBookingPageDto(
        page.items().stream().map(booking -> toBooking(booking, withheld)).toList(),
        List.of(),
        page.hasMore() ? BankExchangeCursor.afterBooking(page.items().getLast().key()) : null,
        page.hasMore());
  }

  /**
   * The daily balance series of a viewable account.
   *
   * @param caller the acting member and client
   * @param accountId the account
   * @param from inclusive period start; required
   * @param to inclusive period end; required
   * @return the series
   * @throws BadRequestException when a bound is missing, {@code from} lies after {@code to} or the
   *     period exceeds {@link #MAX_SERIES_SPAN}
   * @throws NotFoundException {@code 404 NOT_FOUND} for a hidden, closed or unknown account
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeBankBalanceSeriesDto balanceSeries(
      @NotNull ExchangeCaller caller,
      @NotNull UUID accountId,
      @Nullable Instant from,
      @Nullable Instant to) {
    if (from == null || to == null) {
      throw new BadRequestException("A balance series needs both from and to");
    }
    if (from.isAfter(to) || Duration.between(from, to).compareTo(MAX_SERIES_SPAN) > 0) {
      throw new BadRequestException("A balance series covers from <= to and at most 365 days");
    }
    BankAccountReads.BalanceSeries series =
        bankReads.balanceSeries(actor(caller), accountId, from, to).orElseThrow(this::notFound);
    metrics.countRead(caller.clientId(), BankExchangeReadRoute.BALANCE_SERIES);
    return new ExchangeBankBalanceSeriesDto(
        series.points().stream()
            .map(point ->
                new ExchangeBankBalanceSeriesDto.Point(point.date(), whole(point.balance())))
            .toList(),
        wholeOrNull(series.balanceTarget()));
  }

  /**
   * The member's bank actor on the exchange path.
   *
   * @param caller the acting member
   * @return the actor without the Admin role
   */
  @NotNull
  private BankActor actor(@NotNull ExchangeCaller caller) {
    return bankActors.forExchange(caller.member());
  }

  /**
   * Counts and builds the shared {@code 404}.
   *
   * @return the refusal
   */
  @NotNull
  private NotFoundException notFound() {
    metrics.countRefused(BankExchangeRefusal.NOT_FOUND);
    return new NotFoundException("The resource is unknown or hidden from the member.");
  }

  /**
   * Shapes one account.
   *
   * @param account the account read
   * @return the allow-listed account
   */
  @NotNull
  private static ExchangeBankAccountDto toAccount(@NotNull BankAccountReads.Account account) {
    BankAccountReads.Owner owner = account.owner();
    return new ExchangeBankAccountDto(
        account.accountId(),
        account.accountNo(),
        account.name(),
        account.type(),
        owner == null
            ? null
            : new ExchangeOrgUnitDto(owner.id(), owner.name(), owner.shorthand(), owner.kind()),
        whole(account.balance()),
        wholeOrNull(account.balanceTarget()),
        whole(account.delta30d()),
        account.sparkline().stream().map(ExchangeBankReadService::whole).toList(),
        account.requestable(),
        wholeOrNull(account.ownLimit()));
  }

  /**
   * Shapes one booking, without the handle of a counterparty who withholds their name.
   *
   * @param booking the booking read
   * @param withheld the counterparties of the page who withhold their name
   * @return the allow-listed booking
   */
  @NotNull
  private static ExchangeBankBookingDto toBooking(
      @NotNull BankAccountReads.Booking booking, @NotNull Set<UUID> withheld) {
    boolean hidden =
        booking.counterpartyUserId() != null && withheld.contains(booking.counterpartyUserId());
    boolean counter = booking.counterAccountNo() != null || booking.counterAccountName() != null;
    return new ExchangeBankBookingDto(
        booking.postingId(),
        booking.transactionId(),
        booking.type(),
        whole(booking.amount()),
        booking.createdAt(),
        booking.note(),
        booking.justification(),
        booking.reversedTransactionId(),
        counter
            ? new ExchangeBankCounterAccountDto(
                booking.counterAccountNo(), booking.counterAccountName())
            : null,
        booking.intraAccount(),
        booking.transferFee().signum() == 0 ? null : whole(booking.transferFee()),
        hidden ? null : booking.counterpartyHandle(),
        booking.counterpartyOrgUnitName());
  }

  /**
   * An amount as a JSON integer of whole aUEC (ADR-0010).
   *
   * @param amount the whole amount
   * @return its value
   */
  static long whole(@NotNull BigDecimal amount) {
    return amount.longValueExact();
  }

  /**
   * An optional amount as a JSON integer of whole aUEC.
   *
   * @param amount the whole amount, or {@code null}
   * @return its value, or {@code null}
   */
  @Contract("null -> null; !null -> !null")
  static @Nullable Long wholeOrNull(@Nullable BigDecimal amount) {
    return amount == null ? null : whole(amount);
  }
}
