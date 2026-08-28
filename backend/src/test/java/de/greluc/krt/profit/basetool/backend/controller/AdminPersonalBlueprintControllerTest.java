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

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** Unit tests for {@link AdminPersonalBlueprintController}: delegation with the target sub / id. */
@ExtendWith(MockitoExtension.class)
class AdminPersonalBlueprintControllerTest {

  private static final UUID TARGET = UUID.fromString("7a76e700-0000-4000-8000-000000000001");

  @Mock private PersonalBlueprintService service;
  @Mock private BlueprintImportService importService;
  @InjectMocks private AdminPersonalBlueprintController controller;

  private static PersonalBlueprintResponse sample() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new PersonalBlueprintResponse(
        UUID.randomUUID(), "k", "Name", null, null, null, true, 0L, now, now);
  }

  @Test
  void listForUser_usesTargetSubAndWrapsPage() {
    when(service.listForUser(eq(TARGET), any(), any()))
        .thenReturn(new PageImpl<>(List.of(sample()), PageRequest.of(0, 10), 1));

    PageResponse<PersonalBlueprintResponse> result =
        controller.listForUser(TARGET, 0, 10, null, null);

    assertEquals(1, result.totalElements());
    verify(service).listForUser(eq(TARGET), any(), any());
  }

  @Test
  void addForUser_relaysToServiceWithTargetSub() {
    PersonalBlueprintCreateRequest req = new PersonalBlueprintCreateRequest("k", null, null);
    when(service.addForUser(TARGET, req)).thenReturn(sample());

    controller.addForUser(TARGET, req);

    verify(service).addForUser(TARGET, req);
  }

  @Test
  void addBatchForUser_relaysKeysWithTargetSub() {
    PersonalBlueprintBatchCreateRequest req =
        new PersonalBlueprintBatchCreateRequest(List.of("a", "b"));
    when(service.addBatchForUser(TARGET, List.of("a", "b")))
        .thenReturn(new PersonalBlueprintBatchResult(2, 0, 0));

    PersonalBlueprintBatchResult result = controller.addBatchForUser(TARGET, req);

    assertEquals(2, result.added());
    verify(service).addBatchForUser(TARGET, List.of("a", "b"));
  }

  @Test
  void updateForUser_relaysByIdAlone() {
    UUID id = UUID.randomUUID();
    PersonalBlueprintUpdateRequest req = new PersonalBlueprintUpdateRequest(null, "n", 1L);
    when(service.updateForUser(id, req)).thenReturn(sample());

    controller.updateForUser(id, req);

    verify(service).updateForUser(id, req);
  }

  @Test
  void deleteForUser_relaysByIdAlone() {
    UUID id = UUID.randomUUID();

    controller.deleteForUser(id);

    verify(service).deleteForUser(id);
  }

  @Test
  void deleteAllForAllUsers_relaysToServiceAndReturnsCount() {
    when(service.deleteAllForAllUsers()).thenReturn(9);

    var result = controller.deleteAllForAllUsers();

    assertEquals(9, result.deleted());
    verify(service).deleteAllForAllUsers();
  }

  @Test
  void previewImportForUser_relaysFileWithTargetSub() {
    MultipartFile file =
        new MockMultipartFile("file", "scmdb.json", "application/json", "{}".getBytes());
    BlueprintImportPreviewDto preview = new BlueprintImportPreviewDto(0, 0, 0, 0, 0, 0, List.of());
    when(importService.previewImport(TARGET, file)).thenReturn(preview);

    controller.previewImportForUser(TARGET, file);

    verify(importService).previewImport(TARGET, file);
  }

  @Test
  void applyImportForUser_relaysResolutionsWithTargetSub() {
    BlueprintImportResolutionDto res =
        new BlueprintImportResolutionDto("Arclight Pistol", "arclight pistol", null, null);
    BlueprintImportApplyRequest req = new BlueprintImportApplyRequest(List.of(res));
    when(importService.applyImport(TARGET, List.of(res)))
        .thenReturn(new BlueprintImportResultDto(1, 0, 0, 0, 0));

    BlueprintImportResultDto result = controller.applyImportForUser(TARGET, req);

    assertEquals(1, result.added());
    verify(importService).applyImport(TARGET, List.of(res));
  }
}
