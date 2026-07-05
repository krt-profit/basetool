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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardAccountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankDashboardTotalsDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the bank dashboard payload (epic #556, REQ-BANK-016): one KPI card per visible account
 * with balance, 30-day delta and the daily balance series for the server-rendered sparkline, plus
 * the management-only totals strip. Everything derives from THREE statements — the account list,
 * one grouped balance query and one windowed posting-slice query — never from per-account
 * round-trips (REQ-DATA-003). The per-account delta and sparkline series are derived via {@link
 * BankTrendCalculator}, the same helper the org-unit balance page uses, so both surfaces show an
 * identical 30-day trend.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankDashboardService {

  private final BankAccountRepository accountRepository;
  private final BankPostingRepository postingRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Assembles the dashboard for the calling user: management/admin see every account plus the
   * aggregate strip, employees see exactly their granted accounts and no totals (REQ-BANK-010).
   *
   * @param management whether the caller has the management perspective
   * @param userId the caller's user id (the employee filter)
   * @return the dashboard payload
   */
  public BankDashboardDto getDashboard(boolean management, @NotNull UUID userId) {
    List<BankAccount> accounts =
        management
            ? accountRepository.findAllByOrderByAccountNoAsc()
            : accountRepository.findAllGrantedTo(userId);
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();

    Map<UUID, BigDecimal> balances =
        ids.isEmpty()
            ? Map.of()
            : postingRepository.accountBalances(ids).stream()
                .collect(
                    Collectors.toMap(BankAccountBalance::accountId, BankAccountBalance::balance));
    Instant cutoff = BankTrendCalculator.windowCutoff();
    Map<UUID, List<BankPostingSlice>> slices =
        ids.isEmpty()
            ? Map.of()
            : postingRepository.postingSlicesSince(ids, cutoff).stream()
                .collect(Collectors.groupingBy(BankPostingSlice::accountId));

    // Owning org units (with parent pre-loaded) for the by-Bereich grouping (REQ-BANK-016): one
    // bounded query, not one lazy load per account, so the dashboard stays N+1-free (REQ-DATA-003).
    // This is an owner-label read, never an org-unit scope decision (REQ-BANK-008).
    Set<UUID> ownerIds =
        accounts.stream()
            .map(BankAccount::getOrgUnit)
            .filter(Objects::nonNull)
            .map(OrgUnit::getId)
            .collect(Collectors.toSet());
    Map<UUID, OrgUnit> ownersById =
        ownerIds.isEmpty()
            ? Map.of()
            : orgUnitRepository.findAllByIdInWithParent(ownerIds).stream()
                .collect(Collectors.toMap(OrgUnit::getId, Function.identity()));

    List<BankDashboardAccountDto> cards = new ArrayList<>(accounts.size());
    BigDecimal totalBalance = BigDecimal.ZERO;
    BigDecimal inflow = BigDecimal.ZERO;
    BigDecimal outflow = BigDecimal.ZERO;
    long active = 0;
    long closed = 0;
    for (BankAccount account : accounts) {
      BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
      List<BankPostingSlice> accountSlices = slices.getOrDefault(account.getId(), List.of());
      BigDecimal delta = BigDecimal.ZERO;
      for (BankPostingSlice slice : accountSlices) {
        delta = delta.add(slice.amount());
        if (slice.amount().signum() > 0) {
          inflow = inflow.add(slice.amount());
        } else {
          outflow = outflow.add(slice.amount());
        }
      }
      OrgUnit bereich = resolveBereich(account, ownersById);
      cards.add(
          new BankDashboardAccountDto(
              account.getId(),
              account.getAccountNo(),
              account.getName(),
              account.getType(),
              account.getStatus(),
              balance,
              delta,
              BankTrendCalculator.sparkline(balance, delta, accountSlices),
              bereich == null ? null : bereich.getId(),
              bereich == null ? null : bereich.getName(),
              bereich == null ? null : bereich.getDepartment()));
      totalBalance = totalBalance.add(balance);
      if (account.getStatus() == BankAccountStatus.ACTIVE) {
        active++;
      } else {
        closed++;
      }
    }

    BankDashboardTotalsDto totals =
        management
            ? new BankDashboardTotalsDto(totalBalance, inflow, outflow, active, closed)
            : null;
    return new BankDashboardDto(management, cards, totals);
  }

  /**
   * Resolves the Bereich an account groups under for the dashboard's by-Bereich view (REQ-BANK-016)
   * — an owner-label read, never an org-unit scope decision (REQ-BANK-008). An {@code AREA}
   * account's owning org unit is the Bereich itself; a Staffel/SK ({@code ORG_UNIT}) account's
   * Bereich is that owner's parent. {@code CARTEL} (owned by the Organisationsleitung), {@code
   * CARTEL_BANK}, {@code SPECIAL} and an org unit without a Bereich parent resolve to {@code null}.
   *
   * @param account the account whose Bereich to resolve
   * @param ownersById the owning org units (parent pre-loaded) keyed by id
   * @return the owning Bereich org unit, or {@code null} when the account has none
   */
  private OrgUnit resolveBereich(
      @NotNull BankAccount account, @NotNull Map<UUID, OrgUnit> ownersById) {
    OrgUnit ownerRef = account.getOrgUnit();
    if (ownerRef == null) {
      return null;
    }
    OrgUnit owner = ownersById.get(ownerRef.getId());
    if (owner == null) {
      return null;
    }
    if (owner.getKind() == OrgUnitKind.BEREICH) {
      return owner;
    }
    if (owner.getKind() == OrgUnitKind.SQUADRON || owner.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      OrgUnit parent = owner.getParent();
      return parent != null && parent.getKind() == OrgUnitKind.BEREICH ? parent : null;
    }
    return null;
  }
}
