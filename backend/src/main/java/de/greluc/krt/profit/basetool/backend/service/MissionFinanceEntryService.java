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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.backend.repository.FinanceEntryAggregate;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and aggregation for mission finance entries (income and expense rows of a participant).
 * Totals include the profit of refinery orders linked to the mission.
 *
 * <p>Update and delete are gated by {@link MissionSecurityService#canEditFinanceEntry}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionFinanceEntryService {

  private final MissionFinanceEntryRepository financeEntryRepository;
  private final MissionParticipantRepository participantRepository;
  private final MissionRepository missionRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final MissionMapper missionMapper;
  private final AuditService auditService;

  /**
   * Returns paged finance entries for the mission.
   *
   * @param missionId mission id
   * @param pageable page request
   * @return paged finance entries for the mission
   */
  public Page<MissionFinanceEntryDto> getEntriesByMission(UUID missionId, Pageable pageable) {
    return financeEntryRepository.findAllByMissionId(missionId, pageable).map(missionMapper::toDto);
  }

  /**
   * Computes a mission's finance totals with one SQL aggregate plus the linked refinery orders'
   * profit and expenses; {@code null} refinery figures count as 0.
   *
   * @param missionId mission id
   * @return the finance totals (sums coalesced to zero; expense bucket folds in refinery expenses)
   */
  @NotNull
  public MissionFinanceTotalsDto calculateTotals(UUID missionId) {
    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(missionId);
    BigDecimal incomeSum = agg.incomeSum() != null ? agg.incomeSum() : BigDecimal.ZERO;
    long incomeCount = agg.incomeCount() != null ? agg.incomeCount() : 0L;
    BigDecimal financeExpenseSum = agg.expenseSum() != null ? agg.expenseSum() : BigDecimal.ZERO;
    long financeExpenseCount = agg.expenseCount() != null ? agg.expenseCount() : 0L;

    BigDecimal refineryProfit = BigDecimal.ZERO;
    BigDecimal refineryExpenseSum = BigDecimal.ZERO;
    long refineryExpenseCount = 0L;
    List<RefineryOrder> refineryOrders = refineryOrderRepository.findByMissionId(missionId);
    for (RefineryOrder order : refineryOrders) {
      double sales = order.getOreSales() != null ? order.getOreSales() : 0d;
      double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
      double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
      double profit = sales - costs - otherCosts;
      if (profit != 0d) {
        refineryProfit = refineryProfit.add(BigDecimal.valueOf(profit));
      }
      if (costs > 0d) {
        refineryExpenseSum = refineryExpenseSum.add(BigDecimal.valueOf(costs));
        refineryExpenseCount++;
      }
    }

    BigDecimal total = incomeSum.subtract(financeExpenseSum).add(refineryProfit);
    return new MissionFinanceTotalsDto(
        total,
        incomeSum,
        incomeCount,
        financeExpenseSum.add(refineryExpenseSum),
        financeExpenseCount + refineryExpenseCount);
  }

  /**
   * Returns a mission's signed bottom line: income − expense + refinery profit.
   *
   * @param missionId mission id
   * @return signed total in mission credits
   */
  public BigDecimal calculateTotalSum(UUID missionId) {
    return calculateTotals(missionId).total();
  }

  /**
   * Creates a finance entry for a participant of the named mission.
   *
   * @param dto create payload
   * @return the persisted entry
   * @throws NotFoundException when the mission or participant id does not resolve
   * @throws BadRequestException when the participant belongs to a different mission
   */
  @Transactional
  public MissionFinanceEntryDto createEntry(@NotNull MissionFinanceEntryCreateDto dto) {
    Mission mission =
        Entities.require(missionRepository.findById(dto.missionId()), "Mission not found");
    MissionParticipant participant =
        Entities.require(
            participantRepository.findById(dto.participantId()), "Assigned participant not found");

    if (!participant.getMission().getId().equals(mission.getId())) {
      throw new BadRequestException("Participant does not belong to this mission");
    }

    MissionFinanceEntry entry =
        MissionFinanceEntry.builder()
            .mission(mission)
            .participant(participant)
            .note(dto.note())
            .type(dto.type())
            .amount(dto.amount())
            .build();

    MissionFinanceEntry saved = financeEntryRepository.save(entry);
    auditService.record(
        AuditEventType.MISSION_FINANCE_ENTRY_CREATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("entry", entry.getId())
            .with("type", dto.type())
            .with("amount", dto.amount()));
    return missionMapper.toDto(saved);
  }

  /**
   * Updates an existing finance entry after an explicit version check.
   *
   * @param entryId finance entry id
   * @param dto update payload (carries the expected version)
   * @return the persisted entry
   * @throws NotFoundException when the entry does not exist
   * @throws BusinessConflictException when the supplied version no longer matches
   */
  @Transactional
  @PreAuthorize("@missionSecurityService.canEditFinanceEntry(#entryId, authentication)")
  public MissionFinanceEntryDto updateEntry(UUID entryId, MissionFinanceEntryUpdateDto dto) {
    MissionFinanceEntry entry =
        Entities.require(financeEntryRepository.findById(entryId), "Finance entry not found");

    if (!entry.getVersion().equals(dto.version())) {
      throw new BusinessConflictException(
          "The entry has been updated by someone else. Please reload.");
    }

    entry.setNote(dto.note());
    entry.setType(dto.type());
    entry.setAmount(dto.amount());
    auditService.record(
        AuditEventType.MISSION_FINANCE_ENTRY_UPDATED,
        entry.getMission() != null ? entry.getMission().getId() : null,
        entry.getMission() != null ? entry.getMission().getName() : null,
        null,
        AuditDetails.of("entry", entryId).with("type", dto.type()).with("amount", dto.amount()));

    entry = financeEntryRepository.save(entry);
    return missionMapper.toDto(entry);
  }

  /**
   * Deletes a finance entry.
   *
   * @param entryId finance entry id
   * @throws NotFoundException when the entry does not exist
   */
  @Transactional
  @PreAuthorize("@missionSecurityService.canEditFinanceEntry(#entryId, authentication)")
  public void deleteEntry(UUID entryId) {
    MissionFinanceEntry entry =
        Entities.require(financeEntryRepository.findById(entryId), "Finance entry not found");

    UUID auditMissionId = entry.getMission() != null ? entry.getMission().getId() : null;
    String auditMissionName = entry.getMission() != null ? entry.getMission().getName() : null;
    financeEntryRepository.delete(entry);
    auditService.record(
        AuditEventType.MISSION_FINANCE_ENTRY_DELETED,
        auditMissionId,
        auditMissionName,
        null,
        AuditDetails.of("entry", entryId));
  }
}
