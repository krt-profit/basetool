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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryUpdateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceTotalsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.MissionFinanceEntryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure-Mockito unit tests for {@link MissionFinanceEntryController}. The controller's split
 * URL-space (reads are mission-scoped under {@code /missions/{missionId}/finance-entries}, writes
 * are entry-scoped under {@code /finance-entries/{entryId}}) is the easy-to-regress part. The whole
 * finance ledger is restricted to registered members and above (anonymous AND role-less GUEST
 * callers are blocked); that authorization gate lives in {@code @PreAuthorize} and is covered by
 * {@code MissionFinanceEntryControllerSecurityTest}. These tests pin the route topology and the
 * unconditional participant-PII redaction by asserting each handler's pass-through to its specific
 * service method.
 */
@ExtendWith(MockitoExtension.class)
class MissionFinanceEntryControllerTest {

  @Mock private MissionFinanceEntryService service;

  // Real redactor (not a mock) so the participant-PII assertions exercise the actual
  // MissionGuestRedactor logic; @Spy makes @InjectMocks wire it into the controller.
  @org.mockito.Spy
  private de.greluc.krt.profit.basetool.backend.support.MissionGuestRedactor missionGuestRedactor =
      new de.greluc.krt.profit.basetool.backend.support.MissionGuestRedactor();

  @InjectMocks private MissionFinanceEntryController controller;

  private static MissionFinanceEntryDto entry(UUID missionId, FinanceType type, BigDecimal amount) {
    return new MissionFinanceEntryDto(UUID.randomUUID(), missionId, null, "note", type, amount, 1L);
  }

  /**
   * Builds a finance-entry DTO whose nested participant carries a registered user with full PII
   * populated — the shape the service returns before the controller applies the audit-H-1
   * redaction.
   */
  private static MissionFinanceEntryDto entryWithParticipantPii(UUID missionId) {
    UserDto user =
        new UserDto(
            UUID.randomUUID(),
            "bob.callsign",
            "Bob",
            // effectiveName is displayName-or-username by construction, so it mirrors displayName
            // here ("Bob") — never an independent real-name field.
            "Bob",
            "bob@example.invalid",
            5,
            "desc",
            Set.of("ROLE_KRT_MEMBER"),
            Set.of("HANGAR_READ"),
            null,
            true,
            true,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            UUID.randomUUID(), user, null, null, null, null, null, null, null, null, 1L, null);
    return new MissionFinanceEntryDto(
        UUID.randomUUID(),
        missionId,
        participant,
        "note",
        FinanceType.INCOME,
        new BigDecimal("500.00"),
        1L);
  }

  @Test
  void getFinanceEntries_wrapsServicePageIntoPageResponse() {
    UUID missionId = UUID.randomUUID();
    MissionFinanceEntryDto a = entry(missionId, FinanceType.INCOME, new BigDecimal("1000.00"));
    MissionFinanceEntryDto b = entry(missionId, FinanceType.EXPENSE, new BigDecimal("250.00"));
    Page<MissionFinanceEntryDto> page =
        new PageImpl<>(
            List.of(a, b), PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")), 2);
    when(service.getEntriesByMission(eq(missionId), any(Pageable.class))).thenReturn(page);
    // These entries carry no participant, so the unconditional audit-H-1 redaction is a no-op and
    // the page passes through verbatim.

    // Audit finding M-1 (2026-05-20): the controller now builds the {@link Pageable} from
    // explicit page / size / sort params with a whitelist (mirrors UserController / JobOrder).
    PageResponse<MissionFinanceEntryDto> result =
        controller.getFinanceEntries(missionId, 0, 10, "createdAt,asc");

    assertThat(result.content()).containsExactly(a, b);
    assertThat(result.totalElements()).isEqualTo(2L);
    // The sort encoding "<field>,<direction>" is what the frontend's pagination component echoes
    // back on the next request; the shared PageResponse.of factory renders the direction in
    // lowercase, matching the PaginationUtil contract used by every other listing endpoint.
    assertThat(result.sort()).containsExactly("createdAt,asc");
    verify(service).getEntriesByMission(eq(missionId), any(Pageable.class));
  }

  @Test
  void getFinanceEntries_redactsParticipantPiiForEveryCaller() {
    UUID missionId = UUID.randomUUID();
    MissionFinanceEntryDto withPii = entryWithParticipantPii(missionId);
    Page<MissionFinanceEntryDto> page =
        new PageImpl<>(
            List.of(withPii), PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")), 1);
    when(service.getEntriesByMission(eq(missionId), any(Pageable.class))).thenReturn(page);

    PageResponse<MissionFinanceEntryDto> result =
        controller.getFinanceEntries(missionId, 0, 20, "createdAt,desc");

    UserDto user = result.content().get(0).participant().user();
    // Audit H-1: the ledger must never carry a peer's email regardless of the caller's role — email
    // is a profile-only field. Roles / permissions / contextual flags are stripped alongside it.
    assertThat(user.email()).isNull();
    assertThat(user.roles()).isNull();
    assertThat(user.permissions()).isNull();
    assertThat(user.isLogistician()).isFalse();
    // Only the public name tuple survives (consistent with MissionController guest redaction).
    assertThat(user.username()).isEqualTo("bob.callsign");
    assertThat(user.displayName()).isEqualTo("Bob");
    assertThat(user.effectiveName()).isEqualTo("Bob");
  }

  @Test
  void getFinanceEntries_rejectsUnknownSortField() {
    UUID missionId = UUID.randomUUID();

    // Whitelist guard: {@code participant.user.email} is NOT in {@link
    // MissionFinanceEntryController#ALLOWED_SORT} — a 400 here is what the global handler
    // surfaces, so ordering information about PII columns cannot leak via sort.
    assertThatThrownBy(
            () -> controller.getFinanceEntries(missionId, 0, 10, "participant.user.email,desc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getFinanceEntriesSum_delegatesToService() {
    UUID missionId = UUID.randomUUID();
    BigDecimal total = new BigDecimal("12345.67");
    when(service.calculateTotalSum(missionId)).thenReturn(total);

    BigDecimal result = controller.getFinanceEntriesSum(missionId);

    assertThat(result).isEqualByComparingTo(total);
    verify(service).calculateTotalSum(missionId);
  }

  @Test
  void getFinanceSummary_delegatesToService() {
    UUID missionId = UUID.randomUUID();
    MissionFinanceTotalsDto totals =
        new MissionFinanceTotalsDto(
            new BigDecimal("100"), new BigDecimal("300"), 3L, new BigDecimal("200"), 2L);
    when(service.calculateTotals(missionId)).thenReturn(totals);

    MissionFinanceTotalsDto result = controller.getFinanceSummary(missionId);

    assertThat(result).isSameAs(totals);
    verify(service).calculateTotals(missionId);
  }

  @Test
  void getFinanceEntries_capsPageSizeToPerEndpointMax() {
    UUID missionId = UUID.randomUUID();
    when(service.getEntriesByMission(eq(missionId), any(Pageable.class))).thenReturn(Page.empty());

    // A crafted large size must be clamped to the per-endpoint cap (500), not the global 100_000
    // (ADR-0078): the mission finance ledger is not a load-all surface.
    controller.getFinanceEntries(missionId, 0, 100_000, "createdAt,desc");

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
    verify(service).getEntriesByMission(eq(missionId), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
  }

  @Test
  void createFinanceEntry_memberCaller_passesDtoThroughToService() {
    UUID missionId = UUID.randomUUID();
    MissionFinanceEntryCreateDto request =
        new MissionFinanceEntryCreateDto(
            missionId, UUID.randomUUID(), "note", FinanceType.INCOME, new BigDecimal("500.00"));
    MissionFinanceEntryDto created = entry(missionId, FinanceType.INCOME, new BigDecimal("500.00"));
    when(service.createEntry(request)).thenReturn(created);

    // The member/anonymous gate lives in @PreAuthorize (isAuthenticated + isMemberOrAbove +
    // canSeeMission) and is exercised by MissionFinanceEntryControllerSecurityTest. At the pure
    // pass-through level the entry carries no participant, so the unconditional H-1 redaction is a
    // no-op and the persisted DTO comes back by identity.
    MissionFinanceEntryDto result = controller.createFinanceEntry(request);

    assertThat(result).isSameAs(created);
    verify(service).createEntry(request);
  }

  @Test
  void updateFinanceEntry_forwardsBothPathAndBodyToService() {
    UUID entryId = UUID.randomUUID();
    MissionFinanceEntryUpdateDto request =
        new MissionFinanceEntryUpdateDto(
            "updated note", FinanceType.EXPENSE, new BigDecimal("999.99"), 3L);
    MissionFinanceEntryDto updated =
        entry(UUID.randomUUID(), FinanceType.EXPENSE, new BigDecimal("999.99"));
    when(service.updateEntry(entryId, request)).thenReturn(updated);

    MissionFinanceEntryDto result = controller.updateFinanceEntry(entryId, request);

    assertThat(result).isSameAs(updated);
    verify(service).updateEntry(entryId, request);
  }

  @Test
  void deleteFinanceEntry_delegatesToService() {
    UUID entryId = UUID.randomUUID();

    controller.deleteFinanceEntry(entryId);

    verify(service).deleteEntry(entryId);
  }
}
