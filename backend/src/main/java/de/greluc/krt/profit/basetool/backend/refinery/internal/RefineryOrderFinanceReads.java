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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderFigures;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderFinanceReader;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the refinery orders of missions for the operation's finance and payout views. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryOrderFinanceReads implements RefineryOrderFinanceReader {

  private final RefineryOrderRepository refineryOrderRepository;
  private final RefineryOrderMapper refineryOrderMapper;

  @Override
  public @NotNull List<RefineryOrderLine> linesOfMissions(@NotNull List<UUID> missionIds) {
    return lines(refineryOrderRepository.findByMissionIdIn(missionIds));
  }

  @Override
  public @NotNull List<RefineryOrderLine> linesOfMission(@NotNull UUID missionId) {
    return lines(refineryOrderRepository.findByMissionId(missionId));
  }

  @Override
  public @NotNull List<RefineryOrderFigures> figuresOfMissions(@NotNull List<UUID> missionIds) {
    return refineryOrderRepository.findByMissionIdIn(missionIds).stream()
        .map(RefineryOrderFinanceReads::figures)
        .toList();
  }

  @Override
  public @NotNull Map<UUID, Double> profitByMission(@NotNull List<UUID> missionIds) {
    return refineryOrderRepository.aggregateProfitByMissionIds(missionIds).stream()
        .collect(
            Collectors.toMap(
                RefineryMissionProfitAggregate::missionId,
                RefineryMissionProfitAggregate::profitSum));
  }

  private List<RefineryOrderLine> lines(List<RefineryOrder> orders) {
    return orders.stream()
        .map(order -> new RefineryOrderLine(figures(order), refineryOrderMapper.toDto(order)))
        .toList();
  }

  private static RefineryOrderFigures figures(RefineryOrder order) {
    return RefineryOrderFigures.of(
        order.getMissionId(),
        order.getOwner() != null ? order.getOwner().getId() : null,
        order.getOreSales(),
        order.getExpenses(),
        order.getOtherExpenses());
  }
}
