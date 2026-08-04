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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.service.BlueprintCraftabilityService;
import de.greluc.krt.profit.basetool.backend.service.BlueprintImportService;
import de.greluc.krt.profit.basetool.backend.service.PersonalBlueprintService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.multipart.MultipartFile;

/** Unit tests for {@link PersonalBlueprintController}. */
@ExtendWith(MockitoExtension.class)
class PersonalBlueprintControllerTest {

  private static final String SUB = "user-sub-1";

  @Mock private PersonalBlueprintService service;
  @Mock private BlueprintImportService importService;
  @Mock private BlueprintCraftabilityService craftabilityService;
  @Mock private UserService userService;

  @Mock
  private de.greluc.krt.profit.basetool.backend.support.ActingSubjectResolver actingSubjectResolver;

  @InjectMocks private PersonalBlueprintController controller;

  private JwtAuthenticationToken auth;

  @BeforeEach
  void setUp() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", SUB).build();
    auth = new JwtAuthenticationToken(jwt);
  }

  private static PersonalBlueprintResponse sample() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new PersonalBlueprintResponse(
        UUID.randomUUID(), "k", "Name", null, null, null, true, 0L, now, now);
  }

  @Test
  void list_derivesSubAndWrapsInPageResponse() {
    Page<PersonalBlueprintResponse> page =
        new PageImpl<>(List.of(sample()), PageRequest.of(0, 10), 1);
    when(service.listOwn(eq(SUB), any(), any())).thenReturn(page);

    PageResponse<PersonalBlueprintResponse> result = controller.list(0, 10, null, null, SUB);

    assertEquals(1, result.totalElements());
    verify(service).listOwn(eq(SUB), any(), any());
  }

  @Test
  void add_relaysToServiceWithSub() {
    PersonalBlueprintCreateRequest req = new PersonalBlueprintCreateRequest("k", null, null);
    when(service.add(SUB, req)).thenReturn(sample());

    controller.add(req, SUB);

    verify(service).add(SUB, req);
  }

  @Test
  void addBatch_relaysProductKeysWithSub() {
    PersonalBlueprintBatchCreateRequest req =
        new PersonalBlueprintBatchCreateRequest(List.of("a", "b"));
    when(service.addBatch(eq(SUB), eq(List.of("a", "b"))))
        .thenReturn(new PersonalBlueprintBatchResult(2, 0, 0));

    PersonalBlueprintBatchResult result = controller.addBatch(req, SUB);

    assertEquals(2, result.added());
    verify(service).addBatch(SUB, List.of("a", "b"));
  }

  @Test
  void update_relaysToServiceWithSub() {
    UUID id = UUID.randomUUID();
    PersonalBlueprintUpdateRequest req = new PersonalBlueprintUpdateRequest(null, "n", 1L);
    when(service.update(SUB, id, req)).thenReturn(sample());

    controller.update(id, req, SUB);

    verify(service).update(SUB, id, req);
  }

  @Test
  void delete_relaysToServiceWithSub() {
    UUID id = UUID.randomUUID();

    controller.delete(id, SUB);

    verify(service).delete(SUB, id);
  }

  @Test
  void deleteAll_relaysToServiceWithSubAndReturnsCount() {
    when(service.deleteAllOwn(SUB)).thenReturn(3);

    var result = controller.deleteAll(SUB);

    assertEquals(3, result.deleted());
    verify(service).deleteAllOwn(SUB);
  }

  @Test
  void recipe_derivesSubAndRelaysToService() {
    UUID id = UUID.randomUUID();
    PersonalBlueprintRecipeResponse recipe =
        new PersonalBlueprintRecipeResponse("Name", 1, List.of(), List.of());
    when(service.recipeForOwn(SUB, id)).thenReturn(recipe);

    assertSame(recipe, controller.recipe(id, SUB));
    verify(service).recipeForOwn(SUB, id);
  }

  @Test
  void craftability_derivesSubAndUserIdAndRelaysFlag() {
    UUID userId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(any(Jwt.class))).thenReturn(userId);
    List<BlueprintCraftabilityDto> expected = List.of();
    when(craftabilityService.computeForOwner(SUB, userId, true)).thenReturn(expected);

    List<BlueprintCraftabilityDto> result = controller.craftability(true, SUB, auth);

    assertSame(expected, result);
    verify(craftabilityService).computeForOwner(SUB, userId, true);
  }

  @Test
  void previewImport_relaysFileAndSub() {
    MultipartFile file =
        new MockMultipartFile("file", "scmdb.json", "application/json", "{}".getBytes());
    BlueprintImportPreviewDto preview = new BlueprintImportPreviewDto(0, 0, 0, 0, 0, 0, List.of());
    when(importService.previewImport(SUB, file)).thenReturn(preview);

    // The preview endpoint now resolves WHO it acts for, because the ingest gateway may call it on
    // behalf of a member (ADR-0129). For an ordinary caller that resolves to their own sub.
    org.springframework.security.oauth2.jwt.Jwt jwt =
        mock(org.springframework.security.oauth2.jwt.Jwt.class);
    jakarta.servlet.http.HttpServletRequest request =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    when(actingSubjectResolver.resolve(jwt, request)).thenReturn(SUB);

    BlueprintImportPreviewDto result = controller.previewImport(file, jwt, request);

    assertEquals(0, result.total());
    verify(importService).previewImport(SUB, file);
  }

  @Test
  void applyImport_relaysResolutionsWithSub() {
    BlueprintImportResolutionDto res =
        new BlueprintImportResolutionDto("Arclight Pistol", "arclight pistol", null, null);
    BlueprintImportApplyRequest req = new BlueprintImportApplyRequest(List.of(res));
    when(importService.applyImport(SUB, List.of(res)))
        .thenReturn(new BlueprintImportResultDto(1, 0, 0, 0, 0));

    BlueprintImportResultDto result = controller.applyImport(req, SUB);

    assertEquals(1, result.added());
    verify(importService).applyImport(SUB, List.of(res));
  }
}
