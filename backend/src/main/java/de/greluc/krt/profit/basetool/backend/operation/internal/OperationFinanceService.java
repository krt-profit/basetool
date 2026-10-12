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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceGroupAggregate;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderFinanceReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates the finance data of an operation's missions: their finance entries plus the
 * profit/loss of their linked refinery orders.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationFinanceService {

  /**
   * Maximum number of per-mission lines {@link #getOperationFinanceSummary} returns; beyond it the
   * breakdown is clipped and flagged {@code truncated}.
   */
  static final int MAX_FINANCE_SUMMARY_MISSIONS = 500;

  private final OperationRepository operationRepository;
  private final MissionFinanceEntryRepository financeEntryRepository;
  private final MissionMapper missionMapper;
  private final RefineryOrderFinanceReader refineryOrderFinanceReader;

  /**
   * Builds the full aggregated finance DTO for the operation with a fixed number of queries.
   *
   * @param operationId operation primary key
   * @return aggregated finance summary
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no operation
   *     matches the id
   */
  @NotNull
  public OperationFinanceDto getOperationFinances(UUID operationId) {
    Operation operation =
        Entities.require(operationRepository.findById(operationId), "Operation not found");

    List<UUID> missionIds = operation.getMissions().stream().map(Mission::getId).toList();

    if (missionIds.isEmpty()) {
      return new OperationFinanceDto(operationId, BigDecimal.ZERO, List.of());
    }

    List<MissionFinanceEntry> allEntries = financeEntryRepository.findAllByMissionIdIn(missionIds);
    List<RefineryOrderLine> allRefineryOrders =
        refineryOrderFinanceReader.linesOfMissions(missionIds);

    Map<UUID, List<MissionFinanceEntry>> entriesByMission =
        allEntries.stream().collect(Collectors.groupingBy(entry -> entry.getMission().getId()));

    Map<UUID, List<RefineryOrderLine>> refineryOrdersByMission =
        allRefineryOrders.stream()
            .filter(order -> order.figures().missionId() != null)
            .collect(Collectors.groupingBy(order -> order.figures().missionId()));

    BigDecimal operationTotalSum = BigDecimal.ZERO;
    List<MissionFinanceSummaryDto> missionSummaries = new ArrayList<>();

    for (Mission mission : operation.getMissions()) {
      List<MissionFinanceEntry> entries = entriesByMission.getOrDefault(mission.getId(), List.of());
      List<RefineryOrderLine> orders =
          refineryOrdersByMission.getOrDefault(mission.getId(), List.of());

      BigDecimal missionTotalSum = BigDecimal.ZERO;

      for (MissionFinanceEntry entry : entries) {
        if (entry.getType() != null) {
          missionTotalSum = missionTotalSum.add(entry.getType().signed(entry.getAmount()));
        }
      }

      for (RefineryOrderLine order : orders) {
        double profit = order.figures().profit();
        if (profit != 0d) {
          missionTotalSum = missionTotalSum.add(BigDecimal.valueOf(profit));
        }
      }

      operationTotalSum = operationTotalSum.add(missionTotalSum);

      List<MissionFinanceEntryDto> entryDtos = entries.stream().map(missionMapper::toDto).toList();

      List<RefineryOrderDto> orderDtos = orders.stream().map(RefineryOrderLine::order).toList();

      missionSummaries.add(
          new MissionFinanceSummaryDto(
              mission.getId(), mission.getName(), missionTotalSum, entryDtos, orderDtos));
    }

    return new OperationFinanceDto(operationId, operationTotalSum, missionSummaries);
  }

  /**
   * Builds the operation finance roll-up: the operation-wide total plus one total line per mission,
   * from two grouped SQL aggregates (ADR-0078).
   *
   * <p>At most {@link #MAX_FINANCE_SUMMARY_MISSIONS} missions, ordered by name, are returned; when
   * clipped, {@code truncated} is set and the total covers only the returned lines.
   *
   * @param operationId operation primary key
   * @return the operation-wide total plus the capped per-mission lines
   * @throws NotFoundException when no operation matches the id
   */
  @NotNull
  public OperationFinanceSummaryDto getOperationFinanceSummary(UUID operationId) {
    Operation operation =
        Entities.require(operationRepository.findById(operationId), "Operation not found");

    List<Mission> orderedMissions =
        operation.getMissions().stream()
            .sorted(
                Comparator.comparing(
                    Mission::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    boolean truncated = orderedMissions.size() > MAX_FINANCE_SUMMARY_MISSIONS;
    List<Mission> missions =
        truncated ? orderedMissions.subList(0, MAX_FINANCE_SUMMARY_MISSIONS) : orderedMissions;

    if (missions.isEmpty()) {
      return new OperationFinanceSummaryDto(operationId, BigDecimal.ZERO, List.of(), false);
    }

    List<UUID> missionIds = missions.stream().map(Mission::getId).toList();
    Map<UUID, MissionFinanceGroupAggregate> financeByMission =
        financeEntryRepository.aggregateFinanceByMissionIds(missionIds).stream()
            .collect(
                Collectors.toMap(MissionFinanceGroupAggregate::missionId, Function.identity()));
    Map<UUID, Double> refineryProfitByMission =
        refineryOrderFinanceReader.profitByMission(missionIds);

    BigDecimal operationTotalSum = BigDecimal.ZERO;
    List<OperationMissionFinanceDto> lines = new ArrayList<>(missions.size());
    for (Mission mission : missions) {
      MissionFinanceGroupAggregate agg = financeByMission.get(mission.getId());
      BigDecimal missionTotal =
          missionTotal(
              agg != null ? agg.incomeSum() : null,
              agg != null ? agg.expenseSum() : null,
              refineryProfitByMission.get(mission.getId()));
      operationTotalSum = operationTotalSum.add(missionTotal);
      lines.add(new OperationMissionFinanceDto(mission.getId(), mission.getName(), missionTotal));
    }

    return new OperationFinanceSummaryDto(operationId, operationTotalSum, lines, truncated);
  }

  /**
   * Loads one mission's finance entries and refinery orders for the lazy per-mission breakdown of
   * the operation finance panel.
   *
   * @param operationId operation primary key (authorization scope)
   * @param missionId the mission whose detail to load; must belong to the operation
   * @return the mission's finance detail, with the same total as the summary line
   * @throws NotFoundException when the operation does not exist or the mission is not one of its
   *     child missions
   */
  @NotNull
  public MissionFinanceSummaryDto getMissionFinanceDetail(UUID operationId, UUID missionId) {
    Operation operation =
        Entities.require(operationRepository.findById(operationId), "Operation not found");

    Mission mission =
        Entities.require(
            operation.getMissions().stream().filter(m -> m.getId().equals(missionId)).findFirst(),
            "Mission is not part of this operation");

    List<MissionFinanceEntry> entries = financeEntryRepository.findAllByMissionId(missionId);
    List<RefineryOrderLine> orders = refineryOrderFinanceReader.linesOfMission(missionId);

    BigDecimal incomeSum = BigDecimal.ZERO;
    BigDecimal expenseSum = BigDecimal.ZERO;
    for (MissionFinanceEntry entry : entries) {
      switch (entry.getType()) {
        case INCOME -> incomeSum = incomeSum.add(entry.getAmount());
        case EXPENSE -> expenseSum = expenseSum.add(entry.getAmount());
        case null -> {}
      }
    }
    double refineryProfit = 0d;
    for (RefineryOrderLine order : orders) {
      refineryProfit += order.figures().profit();
    }
    BigDecimal missionTotal = missionTotal(incomeSum, expenseSum, refineryProfit);

    List<MissionFinanceEntryDto> entryDtos = entries.stream().map(missionMapper::toDto).toList();
    List<RefineryOrderDto> orderDtos = orders.stream().map(RefineryOrderLine::order).toList();

    return new MissionFinanceSummaryDto(
        mission.getId(), mission.getName(), missionTotal, entryDtos, orderDtos);
  }

  /**
   * Computes a mission's signed total as {@code income − expense + refineryProfit}, treating {@code
   * null} sums as zero.
   *
   * @param incomeSum summed INCOME amount, or {@code null} when none
   * @param expenseSum summed EXPENSE amount, or {@code null} when none
   * @param refineryProfit summed refinery {@code sales − expenses − otherExpenses}, or {@code null}
   *     when none
   * @return the mission's signed total in aUEC
   */
  @NotNull
  private static BigDecimal missionTotal(
      @Nullable BigDecimal incomeSum,
      @Nullable BigDecimal expenseSum,
      @Nullable Double refineryProfit) {
    BigDecimal income = incomeSum != null ? incomeSum : BigDecimal.ZERO;
    BigDecimal expense = expenseSum != null ? expenseSum : BigDecimal.ZERO;
    BigDecimal profit =
        (refineryProfit != null && refineryProfit != 0d)
            ? BigDecimal.valueOf(refineryProfit)
            : BigDecimal.ZERO;
    return income.subtract(expense).add(profit);
  }
}
