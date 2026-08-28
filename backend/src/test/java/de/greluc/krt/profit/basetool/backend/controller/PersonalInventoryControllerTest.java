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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.PersonalInventoryItemService;
import java.time.Instant;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class PersonalInventoryControllerTest {

  private static final UUID SUB = UUID.fromString("42424242-4242-4242-4242-424242424242");

  @Mock private PersonalInventoryItemService service;

  @InjectMocks private PersonalInventoryController controller;

  @Test
  void listShouldDeriveOwnerSubFromJwtAndReturnPageResponse() {
    // Given
    Page<PersonalInventoryItemResponse> page =
        new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 10), 1);
    when(service.listOwn(eq(SUB), any(), any())).thenReturn(page);

    // When
    PageResponse<PersonalInventoryItemResponse> result = controller.list(0, 10, null, null, SUB);

    // Then
    assertNotNull(result);
    assertEquals(1, result.totalElements());
    assertEquals(1, result.content().size());
    verify(service).listOwn(eq(SUB), any(), any());
  }

  @Test
  void createShouldDelegateToServiceWithJwtSub() {
    // Given
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("x", null, 1, PersonalInventoryLocationType.CITY, 1);
    PersonalInventoryItemResponse expected = sampleResponse();
    when(service.createOwn(SUB, req)).thenReturn(expected);

    // When
    PersonalInventoryItemResponse result = controller.create(req, SUB);

    // Then
    assertSame(expected, result);
    ArgumentCaptor<UUID> subCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createOwn(subCaptor.capture(), eq(req));
    assertEquals(
        SUB,
        subCaptor.getValue(),
        "Owner identifier must come from the authenticated caller, never from the request body.");
  }

  @Test
  void updateShouldPropagatePathIdAndJwtSub() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "y", null, 1, PersonalInventoryLocationType.CITY, 1, 0L);
    when(service.updateOwn(SUB, id, req)).thenReturn(sampleResponse());

    controller.update(id, req, SUB);

    verify(service).updateOwn(SUB, id, req);
  }

  @Test
  void deleteShouldPropagatePathIdAndJwtSub() {
    UUID id = UUID.randomUUID();

    controller.delete(id, SUB);

    verify(service).deleteOwn(SUB, id);
  }

  private static PersonalInventoryItemResponse sampleResponse() {
    return new PersonalInventoryItemResponse(
        UUID.randomUUID(),
        "x",
        null,
        1,
        PersonalInventoryLocationType.CITY,
        "Lorville",
        1,
        0L,
        Instant.now(),
        Instant.now());
  }
}
