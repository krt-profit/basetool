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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ConfirmBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.RejectBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.service.BankBookingRequestService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

/**
 * Thin delegation tests for {@link BankRequestController}: the queue defaults to {@code PENDING}
 * and is relayed into a {@link PageResponse}; confirm/reject forward their payload and the current
 * authentication. The capability/visibility decisions and lifecycle invariants are pinned by {@link
 * de.greluc.krt.profit.basetool.backend.service.BankBookingRequestServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class BankRequestControllerTest {

  @Mock private BankBookingRequestService bankBookingRequestService;

  @InjectMocks private BankRequestController controller;

  @Test
  void getQueue_defaultsToPendingAndWrapsPageResponse() {
    BankBookingRequestDto dto = requestDto();
    Page<BankBookingRequestDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
    when(bankBookingRequestService.listQueue(
            eq(BankBookingRequestStatus.PENDING), any(Pageable.class)))
        .thenReturn(page);

    PageResponse<BankBookingRequestDto> result = controller.getQueue(null, null, null, null);

    assertEquals(1, result.totalElements());
    assertSame(dto, result.content().getFirst());
  }

  @Test
  void confirm_delegatesPayloadAndAuthentication() {
    UUID id = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto();
    when(bankBookingRequestService.confirm(
            eq(id), eq(holderId), eq(null), eq(false), eq(2L), any()))
        .thenReturn(dto);

    assertSame(
        dto,
        controller.confirm(id, new ConfirmBankBookingRequest(holderId, null, false, 2L), null));
    verify(bankBookingRequestService)
        .confirm(eq(id), eq(holderId), eq(null), eq(false), eq(2L), any());
  }

  @Test
  void reject_delegatesReasonAndVersion() {
    UUID id = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto();
    when(bankBookingRequestService.reject(eq(id), eq("duplicate"), eq(1L), any())).thenReturn(dto);

    assertSame(dto, controller.reject(id, new RejectBankBookingRequest("duplicate", 1L), null));
    verify(bankBookingRequestService).reject(eq(id), eq("duplicate"), eq(1L), any());
  }

  private static BankBookingRequestDto requestDto() {
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
        0L);
  }
}
