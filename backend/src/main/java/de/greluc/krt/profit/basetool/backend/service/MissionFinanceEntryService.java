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
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD plus aggregation for mission finance entries (income / expense rows attached to a
 * participant).
 *
 * <p>The total-sum aggregation also folds in the profit/loss of refinery orders linked to the
 * mission (ore sales minus expenses minus other expenses). Refinery orders surface here because the
 * mission finance page is the single source of truth for a mission's bottom line — splitting
 * refinery-derived profit into its own page would force users to mentally combine two numbers.
 *
 * <p>Update and delete are gated by {@code @PreAuthorize} on {@link
 * MissionSecurityService#canEditFinanceEntry} — admins/officers can edit any entry, the
 * participant's user can edit only their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionFinanceEntryService {

  private final MissionFinanceEntryRepository financeEntryRepository;
  private final MissionParticipantRepository participantRepository;
  private final MissionRepository missionRepository;
  private final de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository
      refineryOrderRepository;
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
   * Aggregated finance totals for a mission's summary strip: per-type sum + count of the finance
   * entries computed by ONE SQL aggregate (never a row load-all) plus the refinery-order
   * contribution. Under the multi-user mission-page live-update fan-out (ADR-0078) this must not
   * scale with the ledger size — the previous {@code size=1000} load-all pinned a database
   * connection per render. Refinery orders are a small, bounded per-mission list: each contributes
   * its profit ({@code oreSales − expenses − otherExpenses}) to {@link
   * MissionFinanceTotalsDto#total()} and its raw expenses (where &gt; 0) to the expense bucket,
   * matching the previous in-frontend summation. Legacy refinery rows with null sales/expenses are
   * treated as 0.
   *
   * @param missionId mission id
   * @return the finance totals (sums coalesced to zero; expense bucket folds in refinery expenses)
   */
  public MissionFinanceTotalsDto calculateTotals(UUID missionId) {
    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(missionId);
    BigDecimal incomeSum = agg.incomeSum() != null ? agg.incomeSum() : BigDecimal.ZERO;
    long incomeCount = agg.incomeCount() != null ? agg.incomeCount() : 0L;
    BigDecimal financeExpenseSum = agg.expenseSum() != null ? agg.expenseSum() : BigDecimal.ZERO;
    long financeExpenseCount = agg.expenseCount() != null ? agg.expenseCount() : 0L;

    // Refinery orders are bounded per mission (not the unbounded size=1000 ledger), so
    // materializing
    // them stays cheap. Each folds its profit into the signed total and its raw expenses (> 0) into
    // the expense bucket, exactly as the previous frontend loop did.
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
   * Signed bottom line for a mission (finance income − expense + refinery profit). Delegates to
   * {@link #calculateTotals} so the value comes from the SQL aggregate rather than a finance-entry
   * row load-all. Kept as its own method because {@code /finance-entries/sum} is a separately
   * published endpoint reused by the operation finance rollup.
   *
   * @param missionId mission id
   * @return signed total in mission credits
   */
  public BigDecimal calculateTotalSum(UUID missionId) {
    return calculateTotals(missionId).total();
  }

  /**
   * Creates a finance entry. The participant must belong to the named mission; mismatched
   * participant + mission pair surfaces as a 400 {@link BadRequestException} rather than a 500 —
   * distinguishes "bad inputs" from "server bug" for client error handling.
   *
   * @param dto create payload
   * @return the persisted entry
   * @throws NotFoundException when the mission or participant id does not resolve
   * @throws BadRequestException when the participant belongs to a different mission
   */
  @Transactional
  public MissionFinanceEntryDto createEntry(MissionFinanceEntryCreateDto dto) {
    Mission mission =
        missionRepository
            .findById(dto.missionId())
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    MissionParticipant participant =
        participantRepository
            .findById(dto.participantId())
            .orElseThrow(() -> new NotFoundException("Assigned participant not found"));

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
   * Updates an existing finance entry. Optimistic-lock check is explicit (the DTO carries the
   * expected version) and surfaces as {@link BusinessConflictException} → 409 rather than Spring's
   * automatic {@code ObjectOptimisticLockingFailureException}: this entity is only mutated through
   * this service, so explicit checks keep the error path readable.
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
        financeEntryRepository
            .findById(entryId)
            .orElseThrow(() -> new NotFoundException("Finance entry not found"));

    // Optimistic Locking Check
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
        financeEntryRepository
            .findById(entryId)
            .orElseThrow(() -> new NotFoundException("Finance entry not found"));

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
