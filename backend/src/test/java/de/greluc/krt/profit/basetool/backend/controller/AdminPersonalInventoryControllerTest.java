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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class AdminPersonalInventoryControllerTest {

  /** The {@code app_user.id} the admin endpoints address, taken from the path. */
  private static final UUID TARGET = UUID.fromString("7a76e700-0000-4000-8000-000000000002");

  @Mock private PersonalInventoryItemService service;

  @InjectMocks private AdminPersonalInventoryController controller;

  @Test
  void listForUserShouldDelegateToServiceWithPathSub() {
    // Given
    Page<PersonalInventoryItemResponse> page = new PageImpl<>(List.of(sampleResponse()));
    when(service.listForUser(eq(TARGET), any(), any())).thenReturn(page);

    // When
    PageResponse<PersonalInventoryItemResponse> result =
        controller.listForUser(TARGET, 0, 10, null, null);

    // Then
    assertEquals(1, result.content().size());
    verify(service).listForUser(eq(TARGET), any(), any());
  }

  @Test
  void createForUserShouldUsePathSubAsOwner() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("x", null, 1, PersonalInventoryLocationType.CITY, 1);
    when(service.createForUser(TARGET, req)).thenReturn(sampleResponse());

    controller.createForUser(TARGET, req);

    verify(service).createForUser(TARGET, req);
  }

  @Test
  void updateForUserShouldDelegateById() {
    UUID id = UUID.randomUUID();
    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "x", null, 1, PersonalInventoryLocationType.CITY, 1, 0L);
    when(service.updateForUser(id, req)).thenReturn(sampleResponse());

    controller.updateForUser(id, req);

    verify(service).updateForUser(id, req);
  }

  @Test
  void deleteForUserShouldDelegateById() {
    UUID id = UUID.randomUUID();

    controller.deleteForUser(id);

    verify(service).deleteForUser(id);
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
