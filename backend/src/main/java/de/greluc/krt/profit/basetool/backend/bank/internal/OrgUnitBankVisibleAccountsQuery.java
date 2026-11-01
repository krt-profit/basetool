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

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pages the accounts the caller may view on the org-unit bank page in the fixed backend order of
 * REQ-BANK-064: the KRT ({@code CARTEL}) account, then the accounts of the active org-unit context,
 * then the rest, each part by account number with {@code id} as tiebreaker.
 *
 * <p>The set and the active context come from {@link OrgUnitBankAccessService}; balances and trends
 * are loaded in batch for the requested page only.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankVisibleAccountsQuery {

  /** Order rank of the KRT account. */
  private static final int RANK_KRT = 0;

  /** Order rank of an account owned by the active org-unit context. */
  private static final int RANK_ACTIVE_CONTEXT = 1;

  /** Order rank of every other account. */
  private static final int RANK_REST = 2;

  private final OrgUnitBankAccessService orgUnitBankAccessService;
  private final BankPostingRepository bankPostingRepository;

  /**
   * Returns one page of the caller's viewable org-unit bank accounts with balance, target and
   * 30-day trend.
   *
   * @param pageable page index and size; any sort is ignored
   * @return the page, with the total over the whole visible set
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<OrgUnitBankVisibleAccountDto> listVisibleAccounts(@NotNull Pageable pageable) {
    Set<UUID> activeContext = orgUnitBankAccessService.activeContextOrgUnitIds();
    List<BankAccount> ordered =
        orgUnitBankAccessService.listViewableActiveAccounts().stream()
            .sorted(order(activeContext))
            .toList();
    int from = (int) Math.min(pageable.getOffset(), ordered.size());
    int to = Math.min(from + pageable.getPageSize(), ordered.size());
    List<BankAccount> slice = ordered.subList(from, to);
    if (slice.isEmpty()) {
      return new PageImpl<>(List.of(), pageable, ordered.size());
    }
    BankAccountFigures figures = BankAccountFigures.load(bankPostingRepository, slice);
    return new PageImpl<>(
        slice.stream().map(account -> toDto(account, figures)).toList(), pageable, ordered.size());
  }

  /**
   * The fixed order of REQ-BANK-064.
   *
   * @param activeContext the active-context org-unit ids
   * @return the comparator
   */
  @NotNull
  private static Comparator<BankAccount> order(@NotNull Set<UUID> activeContext) {
    return Comparator.comparingInt((BankAccount account) -> rank(account, activeContext))
        .thenComparing(BankAccount::getAccountNo)
        .thenComparing(BankAccount::getId);
  }

  /**
   * The order rank of an account.
   *
   * @param account the account
   * @param activeContext the active-context org-unit ids
   * @return {@link #RANK_KRT}, {@link #RANK_ACTIVE_CONTEXT} or {@link #RANK_REST}
   */
  private static int rank(@NotNull BankAccount account, @NotNull Set<UUID> activeContext) {
    if (account.getType() == BankAccountType.CARTEL) {
      return RANK_KRT;
    }
    OrgUnit owner = account.getOrgUnit();
    return owner != null && activeContext.contains(owner.getId()) ? RANK_ACTIVE_CONTEXT : RANK_REST;
  }

  /**
   * Projects an account and its figures into the response record.
   *
   * @param account the account
   * @param figures the figures of the page's accounts
   * @return the response record
   */
  @NotNull
  private static OrgUnitBankVisibleAccountDto toDto(
      @NotNull BankAccount account, @NotNull BankAccountFigures figures) {
    OrgUnit orgUnit = account.getOrgUnit();
    BigDecimal balance = figures.balanceOf(account.getId());
    List<BankPostingSlice> slices = figures.slicesOf(account.getId());
    BigDecimal delta = BankTrendCalculator.windowDelta(slices);
    return new OrgUnitBankVisibleAccountDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        orgUnit == null ? null : orgUnit.getKind(),
        balance,
        account.getBalanceTarget(),
        delta,
        BankTrendCalculator.sparkline(balance, delta, slices));
  }
}
