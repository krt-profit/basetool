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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestService;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.bank.internal.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Thin delegation tests for {@link BankRequestController}: the queue defaults to {@code PENDING}
 * and is relayed into a {@link PageResponse}; confirm/reject forward their payload and the current
 * authentication. The capability/visibility decisions and lifecycle invariants are pinned by {@link
 * de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class BankRequestControllerTest {

  @Mock private BankBookingRequestService bankBookingRequestService;

  @InjectMocks private BankRequestController controller;

  @Test
  void getQueue_defaultsToPendingAndWrapsPageResponse() {
    BankBookingRequestDto dto = requestDto(null);
    Page<BankBookingRequestDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(bankBookingRequestService.listQueue(
            eq(Set.of(BankBookingRequestStatus.PENDING)), any(Pageable.class), any()))
        .thenReturn(page);

    PageResponse<BankBookingRequestDto> result = controller.getQueue(null, null, null, null, null);

    assertEquals(1, result.totalElements());
    assertSame(dto, result.content().getFirst());
  }

  @Test
  void getQueue_passesSelectedStatusesThrough() {
    Page<BankBookingRequestDto> page =
        new PageImpl<>(List.of(requestDto(null)), PageRequest.of(0, 20), 1);
    Set<BankBookingRequestStatus> selected =
        Set.of(BankBookingRequestStatus.CONFIRMED, BankBookingRequestStatus.REJECTED);
    when(bankBookingRequestService.listQueue(eq(selected), any(Pageable.class), any()))
        .thenReturn(page);

    PageResponse<BankBookingRequestDto> result =
        controller.getQueue(selected, null, null, null, null);

    assertEquals(1, result.totalElements());
    verify(bankBookingRequestService).listQueue(eq(selected), any(Pageable.class), any());
  }

  /** The caller's authentication reaches the service, which judges every row's confirm action. */
  @Test
  void getQueue_relaysTheCallerAuthentication() {
    Authentication caller = new TestingAuthenticationToken("employee", "n/a", "ROLE_BANK_EMPLOYEE");
    when(bankBookingRequestService.listQueue(any(), any(Pageable.class), same(caller)))
        .thenReturn(Page.empty());

    controller.getQueue(null, null, null, null, caller);

    verify(bankBookingRequestService).listQueue(any(), any(Pageable.class), same(caller));
  }

  /** Over HTTP, each queue row carries {@code callerMayConfirm} as the service decided it. */
  @Test
  void getQueue_serialisesCallerMayConfirmPerRow() throws Exception {
    Authentication caller = new TestingAuthenticationToken("employee", "n/a", "ROLE_BANK_EMPLOYEE");
    Page<BankBookingRequestDto> page =
        new PageImpl<>(List.of(requestDto(true), requestDto(false)), PageRequest.of(0, 20), 2);
    when(bankBookingRequestService.listQueue(
            eq(Set.of(BankBookingRequestStatus.PENDING)), any(Pageable.class), same(caller)))
        .thenReturn(page);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc
        .perform(get("/api/v1/bank/requests").principal(caller))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].callerMayConfirm").value(true))
        .andExpect(jsonPath("$.content[1].callerMayConfirm").value(false));
  }

  @Test
  void confirm_delegatesPayloadAndAuthentication() {
    UUID id = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto(null);
    when(bankBookingRequestService.confirm(
            eq(id), eq(holderId), eq(null), eq(false), eq(null), eq(2L), any()))
        .thenReturn(dto);

    assertSame(
        dto,
        controller.confirm(
            id, new ConfirmBankBookingRequest(holderId, null, false, null, 2L), null));
    verify(bankBookingRequestService)
        .confirm(eq(id), eq(holderId), eq(null), eq(false), eq(null), eq(2L), any());
  }

  @Test
  void reject_delegatesReasonAndVersion() {
    UUID id = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto(null);
    when(bankBookingRequestService.reject(eq(id), eq("duplicate"), eq(1L), any())).thenReturn(dto);

    assertSame(dto, controller.reject(id, new RejectBankBookingRequest("duplicate", 1L), null));
    verify(bankBookingRequestService).reject(eq(id), eq("duplicate"), eq(1L), any());
  }

  /**
   * Builds a pending deposit request row.
   *
   * @param callerMayConfirm the row's confirm capability, or {@code null} when not judged
   * @return the row
   */
  private static BankBookingRequestDto requestDto(Boolean callerMayConfirm) {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        UUID.randomUUID(),
        "IRIDIUM",
        "IRI",
        BankBookingRequestType.DEPOSIT,
        new BigDecimal("500"),
        "note",
        null,
        null,
        BankBookingRequestStatus.PENDING,
        "requester",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.now(),
        null,
        null,
        false,
        null,
        null,
        false,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        0L,
        callerMayConfirm);
  }
}
