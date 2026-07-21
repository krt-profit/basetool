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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.service.MaterialRequestBoardService;
import de.greluc.krt.profit.basetool.backend.service.MaterialRequestService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Delegation coverage for {@link MaterialRequestController}: each endpoint forwards its arguments
 * to its collaborator — the read endpoints to {@link MaterialRequestBoardService}, the write
 * endpoints to {@link MaterialRequestService} — and returns the result unchanged (the security gate
 * and validation are framework-enforced by the {@code @PreAuthorize}/{@code @Valid} annotations).
 */
@ExtendWith(MockitoExtension.class)
class MaterialRequestControllerTest {

  @Mock private MaterialRequestService service;
  @Mock private MaterialRequestBoardService boardService;

  @InjectMocks private MaterialRequestController controller;

  private final UUID requestId = UUID.randomUUID();

  @Test
  void boardDelegatesFiltersAndSort() {
    PageResponse<MaterialRequestDto> page =
        new PageResponse<>(List.of(sampleDto()), 0, 50, 1, 1, List.of());
    when(boardService.board("mein", "agri", 600, 100.0, "menge", 0, 50)).thenReturn(page);

    PageResponse<MaterialRequestDto> result =
        controller.board("mein", "agri", 600, 100.0, "menge", 0, 50);

    assertThat(result).isSameAs(page);
    verify(boardService).board("mein", "agri", 600, 100.0, "menge", 0, 50);
  }

  @Test
  void countsDelegates() {
    MaterialExchangeCountsDto counts = new MaterialExchangeCountsDto(5, 2);
    when(boardService.counts()).thenReturn(counts);

    assertThat(controller.counts()).isSameAs(counts);
  }

  @Test
  void detailDelegates() {
    MaterialRequestDto dto = sampleDto();
    when(boardService.detail(requestId)).thenReturn(dto);

    assertThat(controller.detail(requestId)).isSameAs(dto);
  }

  @Test
  void createMaterialRequestDelegates() {
    MaterialRequestCreateRequest request =
        new MaterialRequestCreateRequest(UUID.randomUUID(), 600, 120.0, "suche gegen X");
    MaterialRequestDto dto = sampleDto();
    when(service.createMaterialRequest(request)).thenReturn(dto);

    assertThat(controller.createMaterialRequest(request)).isSameAs(dto);
    verify(service).createMaterialRequest(request);
  }

  @Test
  void createItemRequestDelegates() {
    MaterialItemRequestCreateRequest request =
        new MaterialItemRequestCreateRequest("venture helmet", null, 5, "gegen aUEC");
    MaterialRequestDto dto = sampleDto();
    when(service.createItemRequest(request)).thenReturn(dto);

    assertThat(controller.createItemRequest(request)).isSameAs(dto);
    verify(service).createItemRequest(request);
  }

  @Test
  void updateRequestDelegates() {
    MaterialRequestUpdateRequest request = new MaterialRequestUpdateRequest(50.0, 500, "neu", 3L);
    when(service.updateRequest(requestId, request)).thenReturn(sampleDto());

    controller.updateRequest(requestId, request);

    verify(service).updateRequest(requestId, request);
  }

  @Test
  void deactivateDelegates() {
    when(service.deactivate(requestId)).thenReturn(sampleDto());

    controller.deactivate(requestId);

    verify(service).deactivate(requestId);
  }

  @Test
  void signalFulfillmentDelegates() {
    when(service.signalFulfillment(requestId)).thenReturn(sampleDto());

    controller.signalFulfillment(requestId);

    verify(service).signalFulfillment(requestId);
  }

  @Test
  void withdrawFulfillmentDelegates() {
    when(service.withdrawFulfillment(requestId)).thenReturn(sampleDto());

    controller.withdrawFulfillment(requestId);

    verify(service).withdrawFulfillment(requestId);
  }

  private MaterialRequestDto sampleDto() {
    return new MaterialRequestDto(
        requestId,
        MaterialExchangeRequestKind.MATERIAL,
        new MaterialReferenceDto(UUID.randomUUID(), "Agricium", QuantityType.SCU),
        null,
        null,
        120.0,
        600,
        new UserReferenceDto(UUID.randomUUID(), "Suchende", "Suchende", "Suchende", null),
        List.of(),
        false,
        Instant.now(),
        "suche gegen **Titanium**",
        0,
        null,
        false,
        MaterialExchangeRequestStatus.ACTIVE,
        0L);
  }
}
