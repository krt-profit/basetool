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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.FinanceEntryAggregate;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceContribution;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceContributor;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import java.math.BigDecimal;
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
  private final MissionFinanceContributor financeContributor;
  private final MissionMapper missionMapper;
  private final AuditRecorder auditRecorder;

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
   * Computes a mission's finance totals with one SQL aggregate plus the {@link
   * MissionFinanceContributor}'s share (the linked refinery orders' profit and expenses).
   *
   * @param missionId mission id
   * @return the finance totals (sums coalesced to zero; the expense bucket folds in the
   *     contributor's expenses)
   */
  @NotNull
  public MissionFinanceTotalsDto calculateTotals(UUID missionId) {
    FinanceEntryAggregate agg = financeEntryRepository.aggregateFinanceByMission(missionId);
    BigDecimal incomeSum = agg.incomeSum() != null ? agg.incomeSum() : BigDecimal.ZERO;
    long incomeCount = agg.incomeCount() != null ? agg.incomeCount() : 0L;
    BigDecimal financeExpenseSum = agg.expenseSum() != null ? agg.expenseSum() : BigDecimal.ZERO;
    long financeExpenseCount = agg.expenseCount() != null ? agg.expenseCount() : 0L;

    MissionFinanceContribution linked = financeContributor.contributionTo(missionId);

    BigDecimal total = incomeSum.subtract(financeExpenseSum).add(linked.profit());
    return new MissionFinanceTotalsDto(
        total,
        incomeSum,
        incomeCount,
        financeExpenseSum.add(linked.expenseSum()),
        financeExpenseCount + linked.expenseCount());
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
    auditRecorder.record(
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
   * Loads an entry of the given mission.
   *
   * @param missionId the mission from the request path
   * @param entryId the entry from the request path
   * @return the entry
   * @throws NotFoundException when the entry does not exist or belongs to another mission
   */
  private MissionFinanceEntry requireEntryOf(UUID missionId, UUID entryId) {
    MissionFinanceEntry entry =
        Entities.require(financeEntryRepository.findById(entryId), "Finance entry not found");
    if (entry.getMission() == null || !entry.getMission().getId().equals(missionId)) {
      throw new NotFoundException("Finance entry not found");
    }
    return entry;
  }

  /**
   * Updates an existing finance entry after an explicit version check.
   *
   * @param missionId the mission the entry must belong to
   * @param entryId finance entry id
   * @param dto update payload (carries the expected version)
   * @return the persisted entry
   * @throws NotFoundException when the entry does not exist or belongs to another mission
   * @throws BusinessConflictException when the supplied version no longer matches
   */
  @Transactional
  @PreAuthorize("@missionSecurityService.canEditFinanceEntry(#entryId, authentication)")
  public MissionFinanceEntryDto updateEntry(
      UUID missionId, UUID entryId, MissionFinanceEntryUpdateDto dto) {
    MissionFinanceEntry entry = requireEntryOf(missionId, entryId);

    if (!entry.getVersion().equals(dto.version())) {
      throw new BusinessConflictException(
          "The entry has been updated by someone else. Please reload.");
    }

    entry.setNote(dto.note());
    entry.setType(dto.type());
    entry.setAmount(dto.amount());
    auditRecorder.record(
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
   * @param missionId the mission the entry must belong to
   * @param entryId finance entry id
   * @throws NotFoundException when the entry does not exist or belongs to another mission
   */
  @Transactional
  @PreAuthorize("@missionSecurityService.canEditFinanceEntry(#entryId, authentication)")
  public void deleteEntry(UUID missionId, UUID entryId) {
    MissionFinanceEntry entry = requireEntryOf(missionId, entryId);

    UUID auditMissionId = entry.getMission() != null ? entry.getMission().getId() : null;
    String auditMissionName = entry.getMission() != null ? entry.getMission().getName() : null;
    financeEntryRepository.delete(entry);
    auditRecorder.record(
        AuditEventType.MISSION_FINANCE_ENTRY_DELETED,
        auditMissionId,
        auditMissionName,
        null,
        AuditDetails.of("entry", entryId));
  }
}
