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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.MissionFinanceEntryService;
import de.greluc.krt.profit.basetool.backend.support.MissionPeerRedactor;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over mission finance entries: reads are mission-scoped, writes entry-scoped.
 *
 * <p>The ledger is restricted to members and above ({@code @authHelperService.isMemberOrAbove()});
 * every response strips nested participant PII via {@link #redactParticipantPii}. Update/delete are
 * gated on {@link
 * de.greluc.krt.profit.basetool.backend.service.MissionSecurityService#canEditFinanceEntry}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MissionFinanceEntryController {

  /**
   * Whitelisted sort fields for {@link #getFinanceEntries}; any other field yields a 400, so no
   * ordering by PII columns is possible.
   */
  private static final Set<String> ALLOWED_SORT =
      Set.of("createdAt", "amount", "type", "note", "id");

  /**
   * Upper bound on the finance-entry list {@code size}, below the global {@link
   * PaginationUtil#MAX_PAGE_SIZE}; totals come from the summary aggregate instead (ADR-0078).
   */
  private static final int MAX_FINANCE_PAGE_SIZE = 500;

  private final MissionFinanceEntryService financeEntryService;
  private final MissionPeerRedactor missionPeerRedactor;

  /**
   * Paged finance entries for a mission. Sort is whitelisted; unknown fields → 400.
   *
   * @param missionId mission id
   * @param page zero-based page index, defaults to {@code 0}
   * @param size page size, defaults to {@code 20}
   * @param sort comma-separated {@code field,direction} pair, defaults to {@code createdAt,desc};
   *     {@code field} must be one of {@link #ALLOWED_SORT}.
   * @return paged finance-entry DTOs
   */
  @GetMapping("/missions/{missionId}/finance-entries")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#missionId)")
  public PageResponse<MissionFinanceEntryDto> getFinanceEntries(
      @PathVariable UUID missionId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "createdAt,desc") String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, Math.min(size, MAX_FINANCE_PAGE_SIZE), sort, ALLOWED_SORT, "createdAt");
    Page<MissionFinanceEntryDto> entries =
        financeEntryService.getEntriesByMission(missionId, pageable);
    entries = entries.map(this::redactParticipantPii);
    return PageResponse.of(entries);
  }

  /**
   * Returns the signed bottom-line of the mission (entries + refinery profit).
   *
   * @param missionId mission id
   * @return the signed bottom-line of the mission (entries + refinery profit)
   */
  @GetMapping("/missions/{missionId}/finance-entries/sum")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#missionId)")
  public BigDecimal getFinanceEntriesSum(@PathVariable UUID missionId) {
    return financeEntryService.calculateTotalSum(missionId);
  }

  /**
   * Aggregated finance totals for the mission's summary strip, computed by a single SQL aggregate
   * (ADR-0078). Carries no participant PII.
   *
   * @param missionId mission id
   * @return the income/expense sums and counts plus the signed total
   */
  @GetMapping("/missions/{missionId}/finance-entries/summary")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#missionId)")
  public MissionFinanceTotalsDto getFinanceSummary(@PathVariable UUID missionId) {
    return financeEntryService.calculateTotals(missionId);
  }

  /**
   * Creates a finance entry, gated by {@code @missionSecurityService.canCreateFinanceEntry}
   * (REQ-SEC-042): a mission manager in scope books for anyone on the mission, a plain member only
   * for their own participant row.
   *
   * @param dto create payload
   * @return the persisted entry, with nested participant PII stripped
   */
  @PostMapping("/finance-entries")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canCreateFinanceEntry(#dto.missionId(),"
          + " #dto.participantId(), authentication)")
  public MissionFinanceEntryDto createFinanceEntry(
      @RequestBody @Valid MissionFinanceEntryCreateDto dto) {
    return redactParticipantPii(financeEntryService.createEntry(dto));
  }

  /**
   * Updates an entry; the service checks owner-vs-admin, and the response has nested participant
   * PII stripped via {@link #redactParticipantPii}.
   *
   * @param entryId entry id
   * @param dto update payload (carries the expected version)
   * @return the persisted entry, with nested participant PII stripped
   */
  @PutMapping("/finance-entries/{entryId}")
  @PreAuthorize("isAuthenticated()")
  public MissionFinanceEntryDto updateFinanceEntry(
      @PathVariable UUID entryId, @RequestBody @Valid MissionFinanceEntryUpdateDto dto) {
    return redactParticipantPii(financeEntryService.updateEntry(entryId, dto));
  }

  /**
   * Deletes an entry. Service-layer {@code @PreAuthorize} checks owner-vs-admin.
   *
   * @param entryId entry id
   */
  @DeleteMapping("/finance-entries/{entryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void deleteFinanceEntry(@PathVariable UUID entryId) {
    financeEntryService.deleteEntry(entryId);
  }

  /**
   * Strips the nested participant's PII from a finance-entry DTO, unconditionally for every caller,
   * using {@link MissionPeerRedactor#cleanupParticipantForPeer}.
   *
   * @param dto the finance-entry DTO from the service
   * @return a redacted copy, or {@code dto} when it has no participant or user
   */
  private MissionFinanceEntryDto redactParticipantPii(@NotNull MissionFinanceEntryDto dto) {
    MissionParticipantDto participant = dto.participant();
    if (participant == null || participant.user() == null) {
      return dto;
    }
    MissionParticipantDto redacted = missionPeerRedactor.cleanupParticipantForPeer(participant);
    return new MissionFinanceEntryDto(
        dto.id(), dto.missionId(), redacted, dto.note(), dto.type(), dto.amount(), dto.version());
  }
}
