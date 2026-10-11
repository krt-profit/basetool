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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderFigures;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefineryOrderFinanceReadsTest {

  @Mock private RefineryOrderRepository refineryOrderRepository;

  @Mock private RefineryOrderMapper refineryOrderMapper;

  @InjectMocks private RefineryOrderFinanceReads reads;

  private static RefineryOrder order(UUID missionId, User owner, Double sales, Double costs) {
    RefineryOrder order = new RefineryOrder();
    order.setMissionId(missionId);
    order.setOwner(owner);
    order.setOreSales(sales);
    order.setExpenses(costs);
    return order;
  }

  private static RefineryOrderDto dto() {
    return new RefineryOrderDto(
        null, null, null, null, null, null, null, null, null, null, null, null, List.of(), null,
        null, null);
  }

  @Test
  void figuresCountAnAbsentAmountAsZeroAndCarryTheOwnersId() {
    UUID missionId = UUID.randomUUID();
    User owner = new User();
    owner.setId(UUID.randomUUID());
    List<UUID> missions = List.of(missionId);
    when(refineryOrderRepository.findByMissionIdIn(missions))
        .thenReturn(
            List.of(order(missionId, owner, 450.0, 100.0), order(missionId, null, null, 25.0)));

    List<RefineryOrderFigures> figures = reads.figuresOfMissions(missions);

    assertThat(figures)
        .containsExactly(
            new RefineryOrderFigures(missionId, owner.getId(), 450.0, 100.0, 0d),
            new RefineryOrderFigures(missionId, null, 0d, 25.0, 0d));
    assertThat(figures.get(0).profit()).isEqualTo(350.0);
    assertThat(figures.get(1).profit()).isEqualTo(-25.0);
    assertThat(figures.get(0).advancedCosts()).isEqualTo(100.0);
    verifyNoMoreInteractions(refineryOrderMapper);
  }

  @Test
  void linesOfMissionsPairEveryOrderWithItsFiguresAndItsMappedDto() {
    UUID missionId = UUID.randomUUID();
    List<UUID> missions = List.of(missionId);
    RefineryOrder first = order(missionId, null, 10.0, 4.0);
    RefineryOrder second = order(missionId, null, 20.0, 5.0);
    RefineryOrderDto firstDto = dto();
    RefineryOrderDto secondDto = dto();
    when(refineryOrderRepository.findByMissionIdIn(missions)).thenReturn(List.of(first, second));
    when(refineryOrderMapper.toDto(first)).thenReturn(firstDto);
    when(refineryOrderMapper.toDto(second)).thenReturn(secondDto);

    List<RefineryOrderLine> lines = reads.linesOfMissions(missions);

    assertThat(lines)
        .containsExactly(
            new RefineryOrderLine(
                new RefineryOrderFigures(missionId, null, 10.0, 4.0, 0d), firstDto),
            new RefineryOrderLine(
                new RefineryOrderFigures(missionId, null, 20.0, 5.0, 0d), secondDto));
  }

  @Test
  void linesOfMissionReadTheOneMissionsOrders() {
    UUID missionId = UUID.randomUUID();
    RefineryOrder only = order(missionId, null, 1.0, 1.0);
    RefineryOrderDto onlyDto = dto();
    when(refineryOrderRepository.findByMissionId(missionId)).thenReturn(List.of(only));
    when(refineryOrderMapper.toDto(only)).thenReturn(onlyDto);

    assertThat(reads.linesOfMission(missionId))
        .containsExactly(
            new RefineryOrderLine(
                new RefineryOrderFigures(missionId, null, 1.0, 1.0, 0d), onlyDto));
  }

  @Test
  void profitByMissionKeysTheGroupedSumsByMissionId() {
    UUID alpha = UUID.randomUUID();
    UUID bravo = UUID.randomUUID();
    List<UUID> missions = List.of(alpha, bravo);
    when(refineryOrderRepository.aggregateProfitByMissionIds(missions))
        .thenReturn(
            List.of(
                new RefineryMissionProfitAggregate(alpha, 350.0),
                new RefineryMissionProfitAggregate(bravo, -20.5)));

    assertThat(reads.profitByMission(missions)).isEqualTo(Map.of(alpha, 350.0, bravo, -20.5));
    verify(refineryOrderRepository).aggregateProfitByMissionIds(missions);
  }
}
