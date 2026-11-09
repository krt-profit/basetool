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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountAccess;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link BankAccountReads} through the policy ({@link BankAccessRules}) without a pin and
 * the org-unit-blind ledger reads of {@link BankAccountService}; it decides no access itself.
 */
@Service
@RequiredArgsConstructor
public class BankAccountReadService implements BankAccountReads {

  private final BankAccessRules policy;
  private final BankAccountRepository accountRepository;
  private final BankPostingRepository postingRepository;
  private final BankAccountService accountService;

  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Optional<AccountPage> viewableAccounts(
      @NotNull BankActor actor, @Nullable UUID afterAccountId, int limit) {
    List<BankAccessRules.ViewableAccount> ordered =
        policy.viewable(actor, null).stream()
            .sorted(
                Comparator.comparing(
                    BankAccessRules.ViewableAccount::account,
                    OrgUnitBankVisibleAccountsQuery.order(Set.of())))
            .toList();
    int start = 0;
    if (afterAccountId != null) {
      int after = indexOf(ordered, afterAccountId);
      if (after < 0) {
        return Optional.empty();
      }
      start = after + 1;
    }
    int end = Math.min(start + limit, ordered.size());
    List<BankAccessRules.ViewableAccount> slice = ordered.subList(start, end);
    if (slice.isEmpty()) {
      return Optional.of(new AccountPage(List.of(), false));
    }
    BankAccountFigures figures =
        BankAccountFigures.load(
            postingRepository,
            slice.stream().map(BankAccessRules.ViewableAccount::account).toList());
    return Optional.of(
        new AccountPage(
            slice.stream().map(viewable -> toAccount(viewable, figures)).toList(),
            end < ordered.size()));
  }

  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Optional<BookingPage> bookings(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable BookingKey after,
      int limit) {
    if (viewable(actor, accountId).isEmpty()) {
      return Optional.empty();
    }
    List<BankBookingRow> rows =
        postingRepository.findBookingsAfter(
            accountId,
            from,
            to,
            after == null ? null : after.createdAt(),
            after == null ? null : after.postingId(),
            Pageable.ofSize(limit + 1));
    boolean hasMore = rows.size() > limit;
    List<BankBookingRow> page = hasMore ? rows.subList(0, limit) : rows;
    List<BankBookingDto> dtos = accountService.toBookingDtos(page);
    List<Booking> items = new ArrayList<>(page.size());
    for (int i = 0; i < page.size(); i++) {
      items.add(toBooking(dtos.get(i), page.get(i).counterpartyUserId()));
    }
    return Optional.of(new BookingPage(List.copyOf(items), hasMore));
  }

  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Optional<BalanceSeries> balanceSeries(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to) {
    return viewable(actor, accountId)
        .map(account ->
            new BalanceSeries(
                BankBalanceSeriesCalculator.daily(
                        postingRepository.accountBalanceBefore(accountId, from),
                        postingRepository.postingSlicesInRange(accountId, from, to),
                        from,
                        to)
                    .stream()
                    .map(point ->
                        new BalancePoint(
                            point.date().atZone(ZoneOffset.UTC).toLocalDate(), point.balance()))
                    .toList(),
                account.getBalanceTarget()));
  }

  /**
   * Loads an active account the actor views; an unknown, closed or hidden one is absent alike.
   *
   * @param actor the actor
   * @param accountId the account
   * @return the account, or empty
   */
  @NotNull
  private Optional<BankAccount> viewable(@NotNull BankActor actor, @NotNull UUID accountId) {
    return accountRepository
        .findById(accountId)
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .filter(account -> policy.accessTo(actor, account, null).has(BankAccountRight.VIEW));
  }

  /**
   * The position of an account in the ordered list.
   *
   * @param ordered the ordered viewable accounts
   * @param accountId the account
   * @return its index, or {@code -1}
   */
  private static int indexOf(
      @NotNull List<BankAccessRules.ViewableAccount> ordered, @NotNull UUID accountId) {
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).account().getId().equals(accountId)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Projects a viewable account, its figures and the actor's access.
   *
   * @param viewable the account with the actor's access
   * @param figures the figures of the page's accounts
   * @return the account read
   */
  @NotNull
  private static Account toAccount(
      @NotNull BankAccessRules.ViewableAccount viewable, @NotNull BankAccountFigures figures) {
    BankAccount account = viewable.account();
    BankAccountAccess.Visible access = viewable.access();
    BigDecimal balance = figures.balanceOf(account.getId());
    List<BankPostingSlice> slices = figures.slicesOf(account.getId());
    BigDecimal delta = BankTrendCalculator.windowDelta(slices);
    OrgUnit orgUnit = account.getOrgUnit();
    return new Account(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getType().name(),
        orgUnit == null
            ? null
            : new Owner(
                orgUnit.getId(), orgUnit.getShorthand(), orgUnit.getName(), orgUnit.getKind()),
        balance,
        account.getBalanceTarget(),
        delta,
        List.copyOf(BankTrendCalculator.sparkline(balance, delta, slices)),
        access.has(BankAccountRight.REQUEST),
        access.approvalLimit(),
        access.approvalExempt());
  }

  /**
   * Projects a booking row without its holders and staff note (REQ-BANK-038).
   *
   * @param booking the booking row
   * @param counterpartyUserId the registered counterparty, or {@code null}
   * @return the booking read
   */
  @NotNull
  private static Booking toBooking(
      @NotNull BankBookingDto booking, @Nullable UUID counterpartyUserId) {
    return new Booking(
        booking.postingId(),
        booking.transactionId(),
        booking.type().name(),
        booking.amount(),
        booking.createdAt(),
        booking.note(),
        booking.justification(),
        booking.reversedTransactionId(),
        booking.counterAccountNo(),
        booking.counterAccountName(),
        booking.intraAccount(),
        booking.transferFee(),
        counterpartyUserId,
        booking.counterpartyHandle(),
        booking.counterpartyOrgUnitName());
  }
}
