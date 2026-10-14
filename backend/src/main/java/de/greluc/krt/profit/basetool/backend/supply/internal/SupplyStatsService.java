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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The donation statistics (REQ-SUP-013): the trend the pilot's abort criterion needs per receiving
 * unit and month, a member's own totals and, for the governing leadership only, the per-donor
 * totals.
 */
@Service
@RequiredArgsConstructor
public class SupplyStatsService {

  private static final Set<DonationStatus> RECEIVED =
      Set.of(DonationStatus.RECEIVED, DonationStatus.PARTIALLY_RECEIVED);
  private static final int MAX_MONTHS = 36;

  private final SupplyDonationRepository donationRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final SupplySettingsService settingsService;
  private final SupplyAccessPolicy accessPolicy;
  private final AuthHelperService authHelperService;

  /**
   * Computes the unit statistics of the last months.
   *
   * @param months how many months back, the current one included; clamped to 1 … 36
   * @return the figures of the units the caller manages, of every unit for the governing
   *     leadership, and the per-donor totals for the governing leadership
   */
  @Transactional(readOnly = true)
  public @NotNull SupplyStatsDto stats(int months) {
    settingsService.requireEnabled();
    boolean leadership = accessPolicy.isGoverningLeadership();
    Set<UUID> managed = accessPolicy.managedUnitIds();
    if (!leadership && managed.isEmpty()) {
      throw new AccessDeniedException("The caller sees no donation statistics");
    }
    int span = Math.max(1, Math.min(MAX_MONTHS, months));
    YearMonth last = YearMonth.now(ZoneOffset.UTC);
    YearMonth first = last.minusMonths(span - 1L);
    var from = first.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    var to = last.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);

    List<String> monthKeys = new ArrayList<>();
    for (int i = 0; i < span; i++) {
      monthKeys.add(first.plusMonths(i).toString());
    }
    Map<UUID, String> unitNames = new HashMap<>();
    for (OrgUnit unit : orgUnitRepository.findAll()) {
      unitNames.put(unit.getId(), unit.getName());
    }
    Map<String, List<SupplyStatsDto.EntryAmount>> amounts = new HashMap<>();
    for (DonationAmountRow row :
        donationRepository.amountsByUnitMonthAndEntry(RECEIVED, from, to)) {
      amounts
          .computeIfAbsent(key(row.unitId(), row.year(), row.month()), k -> new ArrayList<>())
          .add(new SupplyStatsDto.EntryAmount(row.entry(), row.amount()));
    }
    List<SupplyStatsDto.UnitMonth> units = new ArrayList<>();
    for (DonationStatRow row : donationRepository.statsByUnitAndMonth(RECEIVED, from, to)) {
      if (!leadership && !managed.contains(row.unitId())) {
        continue;
      }
      String key = key(row.unitId(), row.year(), row.month());
      units.add(
          new SupplyStatsDto.UnitMonth(
              row.unitId(),
              unitNames.getOrDefault(row.unitId(), "—"),
              YearMonth.of(row.year(), row.month()).toString(),
              row.donations(),
              row.distinctDonors(),
              amounts.getOrDefault(key, List.of())));
    }
    List<SupplyStatsDto.DonorTotal> donors = leadership ? donorTotals() : List.of();
    return new SupplyStatsDto(monthKeys, units, donors);
  }

  /**
   * Computes the caller's own received donations.
   *
   * @return the number of received donations and the amount per material or item
   */
  @Transactional(readOnly = true)
  public @NotNull MyDonationStatsDto mine() {
    settingsService.requireEnabled();
    UUID caller =
        authHelperService
            .currentUserId()
            .orElseThrow(() -> new AccessDeniedException("No authenticated member"));
    List<DonorAmountRow> rows = donationRepository.amountsByDonor(caller, RECEIVED);
    long donations = rows.stream().mapToLong(DonorAmountRow::donations).sum();
    return new MyDonationStatsDto(
        donations,
        rows.stream()
            .map(row -> new SupplyStatsDto.EntryAmount(row.entry(), row.amount()))
            .toList());
  }

  private @NotNull List<SupplyStatsDto.DonorTotal> donorTotals() {
    List<DonorAmountRow> rows = donationRepository.amountsPerDonor(RECEIVED);
    Map<UUID, String> names = new LinkedHashMap<>();
    Set<UUID> ids = rows.stream().map(DonorAmountRow::donorUserId).collect(Collectors.toSet());
    if (!ids.isEmpty()) {
      for (User user : userRepository.findAllById(ids)) {
        names.put(user.getId(), user.getEffectiveName());
      }
    }
    return rows.stream()
        .map(
            row ->
                new SupplyStatsDto.DonorTotal(
                    names.getOrDefault(row.donorUserId(), "—"),
                    row.entry(),
                    row.donations(),
                    row.amount()))
        .sorted(
            Comparator.comparing(
                    SupplyStatsDto.DonorTotal::donorName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SupplyStatsDto.DonorTotal::entry))
        .toList();
  }

  private static @NotNull String key(UUID unitId, int year, int month) {
    return unitId + "|" + LocalDate.of(year, month, 1);
  }
}
