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
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.MissionFinanceEntryService;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * REST surface over mission finance entries. Reads are mission-scoped (via {@code
 * /missions/{missionId}/finance-entries}); writes are entry-scoped (via {@code
 * /finance-entries/{entryId}}). The whole finance ledger is restricted to registered members and
 * above ({@code @authHelperService.isMemberOrAbove()}): anonymous callers AND authenticated but
 * role-less {@code GUEST} accounts are blocked from reading and creating entries, mirroring the
 * "treat guest like anonymous on the mission surface" rule (the finance ledger is the mission's
 * payout view). Every member-facing response still strips the nested participant PII via {@link
 * #redactParticipantPii} (a peer's email is profile-only; audit finding H-1). Update/delete are
 * gated on {@link
 * de.greluc.krt.profit.basetool.backend.service.MissionSecurityService#canEditFinanceEntry}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MissionFinanceEntryController {

  /**
   * Whitelisted sort fields for {@link #getFinanceEntries}. Anything else from the {@code sort}
   * query parameter triggers {@link IllegalArgumentException} in {@link
   * PaginationUtil#createPageRequest} — the global handler turns that into a 400. Without the
   * whitelist Spring's default resolver accepts paths like {@code participant.user.email,desc},
   * leaking ordering information about PII columns (audit finding M-1).
   */
  private static final Set<String> ALLOWED_SORT =
      Set.of("createdAt", "amount", "type", "note", "id");

  /**
   * Per-endpoint upper bound on the finance-entry list {@code size}, well below the global {@link
   * PaginationUtil#MAX_PAGE_SIZE}. The mission finance ledger is not a "load-all" surface — the
   * summary strip reads the SQL aggregate at {@code /finance-entries/summary} instead — so a modest
   * cap keeps a single render from materializing thousands of rows and pinning a database
   * connection under the multi-user live-update fan-out (ADR-0078).
   */
  private static final int MAX_FINANCE_PAGE_SIZE = 500;

  private final MissionFinanceEntryService financeEntryService;

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
    // Audit H-1: canSeeMission (above) blocks cross-squadron reads of internal missions; on top of
    // that the nested participant PII is stripped for EVERY caller — including Logistician/Officer.
    // A participant's email may only ever be shown to that user themselves in their own profile, so
    // it must never travel to a peer through the finance ledger (there is no business need for a
    // peer's contact data here). redactUserPii keeps only the public name tuple.
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
   * Aggregated finance totals for the mission's summary strip (Gesamtsumme / Einnahmen / Ausgaben /
   * je Anteil), computed by a single SQL aggregate rather than a ledger load-all (ADR-0078
   * mission-scale hardening). Carries only sums and counts — no participant PII — so, unlike the
   * entry list, it needs no redaction.
   *
   * @param missionId mission id
   * @return the mission's finance totals (income/expense sums + counts and the signed total)
   */
  @GetMapping("/missions/{missionId}/finance-entries/summary")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#missionId)")
  public MissionFinanceTotalsDto getFinanceSummary(@PathVariable UUID missionId) {
    return financeEntryService.calculateTotals(missionId);
  }

  /**
   * Creates a finance entry. Restricted to registered members and above ({@code
   * isMemberOrAbove()}): anonymous callers AND authenticated role-less {@code GUEST} accounts are
   * rejected with 401/403, because the finance ledger is the mission's payout view and a guest is
   * treated like an anonymous visitor there. {@code @ownerScopeService.canSeeMission} additionally
   * keeps a member from writing to a mission outside their scope. The response strips the nested
   * participant PII via {@link #redactParticipantPii} so the create cannot echo a peer's email back
   * to the creator (email is a profile-only field, H-1).
   *
   * @param dto create payload
   * @return the persisted entry, with nested participant PII stripped
   */
  @PostMapping("/finance-entries")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#dto.missionId())")
  public MissionFinanceEntryDto createFinanceEntry(
      @RequestBody @Valid MissionFinanceEntryCreateDto dto) {
    // Strip the nested participant PII so the create response cannot echo a peer's email back to
    // the
    // creator (H-1). Defence in depth on top of the email-free UserMapper projection — the
    // controller boundary enforces it regardless of how the DTO was built.
    return redactParticipantPii(financeEntryService.createEntry(dto));
  }

  /**
   * Updates an entry. Service-layer {@code @PreAuthorize} checks owner-vs-admin; the response has
   * its nested participant PII stripped via {@link #redactParticipantPii} (email is a profile-only
   * field; H-1) so an edit cannot echo a peer's email back to the editor.
   *
   * @param entryId entry id
   * @param dto update payload (carries the expected version)
   * @return the persisted entry, with nested participant PII stripped
   */
  @PutMapping("/finance-entries/{entryId}")
  @PreAuthorize("isAuthenticated()")
  public MissionFinanceEntryDto updateFinanceEntry(
      @PathVariable UUID entryId, @RequestBody @Valid MissionFinanceEntryUpdateDto dto) {
    // Strip nested participant PII (email is profile-only; H-1) — mirrors the read / create paths.
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
   * Redacts the nested participant's PII from a finance-entry DTO for every finance-ledger caller
   * (audit H-1) — the redaction is unconditional, a Logistician/Officer is treated no differently
   * from a squadron member here. A {@code null} participant or user passes through unchanged;
   * otherwise the nested user is stripped via {@link #redactUserPii} while the participant's
   * non-sensitive fields (org units, job types, comment, times, payout preference) are kept.
   * Mirrors {@code MissionController#cleanupParticipantForGuest}.
   *
   * @param dto the finance-entry DTO straight from the service
   * @return a copy with the nested participant PII stripped, or {@code dto} when there is no
   *     participant/user to redact
   */
  private MissionFinanceEntryDto redactParticipantPii(MissionFinanceEntryDto dto) {
    MissionParticipantDto participant = dto.participant();
    if (participant == null || participant.user() == null) {
      return dto;
    }
    MissionParticipantDto redacted =
        new MissionParticipantDto(
            participant.id(),
            redactUserPii(participant.user()),
            participant.guestName(),
            participant.orgUnits(),
            participant.desiredMissionJobType(),
            participant.plannedMissionJobType(),
            participant.comment(),
            participant.startTime(),
            participant.endTime(),
            participant.payoutPreference(),
            participant.version(),
            // Finance-entry responses are reads, never a guest sign-up create — no edit token is
            // ever minted or surfaced here (M1).
            null);
    return new MissionFinanceEntryDto(
        dto.id(), dto.missionId(), redacted, dto.note(), dto.type(), dto.amount(), dto.version());
  }

  /**
   * Strips PII from a participant's {@link UserDto} for every finance-ledger caller: nulls email,
   * description, roles, permissions, last-read watermark, squadron and join date and forces the
   * logistician/mission-manager flags to {@code false}; keeps the id, the public name tuple
   * (username/displayName/effectiveName), {@code rank}, {@code inKeycloak} and {@code version}.
   * Email is dropped for everyone because it may only ever be shown to the user themselves in their
   * own profile, never to a peer. {@code effectiveName} is retained — not a real name but, by
   * construction in {@link de.greluc.krt.profit.basetool.backend.model.User#getEffectiveName()},
   * just the display name with a username fallback — because the ledger UI renders the participant
   * column from it. The same contract as {@code MissionController#cleanupUserForGuest} so the
   * redacted shape is consistent across the mission and finance views.
   *
   * @param dto the participant's user DTO
   * @return a redacted copy carrying only the public name tuple and non-sensitive scalars
   */
  private UserDto redactUserPii(UserDto dto) {
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        null, // email
        dto.rank(),
        null, // description
        null, // roles
        null, // permissions
        null, // lastReadAnnouncementId
        false, // isLogistician
        false, // isMissionManager
        dto.inKeycloak(),
        null, // squadron
        null, // squadrons
        dto.version(),
        null, // joinDate
        null // discordLinked – not exposed to guests
        );
  }
}
