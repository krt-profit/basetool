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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mapper.RefineryOrderMapper;
import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationFinanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationMissionFinanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceGroupAggregate;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryMissionProfitAggregate;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
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
  private final RefineryOrderRepository refineryOrderRepository;
  private final MissionMapper missionMapper;
  private final RefineryOrderMapper refineryOrderMapper;

  /**
   * Builds the full aggregated finance DTO for the operation with a fixed number of queries.
   *
   * @param operationId operation primary key
   * @return aggregated finance summary
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no operation
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
    List<RefineryOrder> allRefineryOrders = refineryOrderRepository.findByMissionIdIn(missionIds);

    Map<UUID, List<MissionFinanceEntry>> entriesByMission =
        allEntries.stream().collect(Collectors.groupingBy(entry -> entry.getMission().getId()));

    Map<UUID, List<RefineryOrder>> refineryOrdersByMission =
        allRefineryOrders.stream()
            .filter(order -> order.getMission() != null)
            .collect(Collectors.groupingBy(order -> order.getMission().getId()));

    BigDecimal operationTotalSum = BigDecimal.ZERO;
    List<MissionFinanceSummaryDto> missionSummaries = new ArrayList<>();

    for (Mission mission : operation.getMissions()) {
      List<MissionFinanceEntry> entries = entriesByMission.getOrDefault(mission.getId(), List.of());
      List<RefineryOrder> orders = refineryOrdersByMission.getOrDefault(mission.getId(), List.of());

      BigDecimal missionTotalSum = BigDecimal.ZERO;

      for (MissionFinanceEntry entry : entries) {
        if (entry.getType() == FinanceType.INCOME) {
          missionTotalSum = missionTotalSum.add(entry.getAmount());
        } else if (entry.getType() == FinanceType.EXPENSE) {
          missionTotalSum = missionTotalSum.subtract(entry.getAmount());
        }
      }

      for (RefineryOrder order : orders) {
        double sales = order.getOreSales() != null ? order.getOreSales() : 0d;
        double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
        double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
        double profit = sales - costs - otherCosts;
        if (profit != 0d) {
          missionTotalSum = missionTotalSum.add(BigDecimal.valueOf(profit));
        }
      }

      operationTotalSum = operationTotalSum.add(missionTotalSum);

      List<MissionFinanceEntryDto> entryDtos = entries.stream().map(missionMapper::toDto).toList();

      List<RefineryOrderDto> orderDtos = orders.stream().map(refineryOrderMapper::toDto).toList();

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
        refineryOrderRepository.aggregateProfitByMissionIds(missionIds).stream()
            .collect(
                Collectors.toMap(
                    RefineryMissionProfitAggregate::missionId,
                    RefineryMissionProfitAggregate::profitSum));

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
    List<RefineryOrder> orders = refineryOrderRepository.findByMissionId(missionId);

    BigDecimal incomeSum = BigDecimal.ZERO;
    BigDecimal expenseSum = BigDecimal.ZERO;
    for (MissionFinanceEntry entry : entries) {
      if (entry.getType() == FinanceType.INCOME) {
        incomeSum = incomeSum.add(entry.getAmount());
      } else if (entry.getType() == FinanceType.EXPENSE) {
        expenseSum = expenseSum.add(entry.getAmount());
      }
    }
    double refineryProfit = 0d;
    for (RefineryOrder order : orders) {
      double sales = order.getOreSales() != null ? order.getOreSales() : 0d;
      double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
      double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
      refineryProfit += sales - costs - otherCosts;
    }
    BigDecimal missionTotal = missionTotal(incomeSum, expenseSum, refineryProfit);

    List<MissionFinanceEntryDto> entryDtos = entries.stream().map(missionMapper::toDto).toList();
    List<RefineryOrderDto> orderDtos = orders.stream().map(refineryOrderMapper::toDto).toList();

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
      BigDecimal incomeSum, BigDecimal expenseSum, Double refineryProfit) {
    BigDecimal income = incomeSum != null ? incomeSum : BigDecimal.ZERO;
    BigDecimal expense = expenseSum != null ? expenseSum : BigDecimal.ZERO;
    BigDecimal profit =
        (refineryProfit != null && refineryProfit != 0d)
            ? BigDecimal.valueOf(refineryProfit)
            : BigDecimal.ZERO;
    return income.subtract(expense).add(profit);
  }
}
