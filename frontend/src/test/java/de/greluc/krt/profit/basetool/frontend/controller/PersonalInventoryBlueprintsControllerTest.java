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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintRecipeDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Pure-unit tests for {@link PersonalInventoryBlueprintsPageController}: URL composition of the
 * type-ahead proxy, the multi-select batch relay, and the redirect + flash behavior of the note
 * edit / remove handlers (including the 409 optimistic-lock toast).
 */
@ExtendWith(MockitoExtension.class)
class PersonalInventoryBlueprintsControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private PersonalInventoryBlueprintsPageController controller;

  @Test
  void search_delegatesToBackend_andDefaultsLimitTo25() {
    BlueprintProductDto dto =
        new BlueprintProductDto("arclight pistol", "Arclight Pistol", 2, "Behring", "key", false);
    when(backendApiClient.get(
            contains("/api/v1/blueprints/products/search?q=arc&limit=25"), anyTypeRef()))
        .thenReturn(List.of(dto));

    List<BlueprintProductDto> result = controller.search("arc", null);

    assertEquals(1, result.size());
    assertEquals("arclight pistol", result.get(0).productKey());
  }

  @Test
  void search_clampsLimitTo200() {
    when(backendApiClient.get(contains("limit=200"), anyTypeRef())).thenReturn(List.of());

    assertTrue(controller.search("x", 9999).isEmpty());
  }

  @Test
  void search_returnsEmptyList_whenBackendThrows() {
    when(backendApiClient.get(any(), anyTypeRef())).thenThrow(new RuntimeException("boom"));

    List<BlueprintProductDto> result = controller.search("anything", 25);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void addSelected_relaysKeysToBatchEndpoint() {
    when(backendApiClient.post(
            eq("/api/v1/personal-blueprints/batch"),
            any(PersonalBlueprintBatchCreateRequest.class),
            eq(PersonalBlueprintBatchResultDto.class)))
        .thenReturn(new PersonalBlueprintBatchResultDto(2, 1, 0));

    PersonalBlueprintBatchResultDto result = controller.addSelected(List.of("a", "b", "c"));

    assertEquals(2, result.added());
    assertEquals(1, result.skippedAlreadyOwned());
  }

  @Test
  void addSelected_shortCircuitsOnEmptyList_withoutCallingBackend() {
    PersonalBlueprintBatchResultDto result = controller.addSelected(List.of());

    assertEquals(0, result.added());
    verify(backendApiClient, never()).post(any(), any(), any());
  }

  @Test
  void addSelected_returnsZeroResult_whenBackendThrows() {
    when(backendApiClient.post(any(), any(), eq(PersonalBlueprintBatchResultDto.class)))
        .thenThrow(new RuntimeException("boom"));

    PersonalBlueprintBatchResultDto result = controller.addSelected(List.of("a"));

    assertEquals(0, result.added());
    assertEquals(0, result.skippedUnresolved());
  }

  @Test
  void updateNote_relaysPutAndFlashesSuccess() {
    UUID id = UUID.randomUUID();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String view = controller.updateNote(id, "a note", "2026-03-01T00:00:00Z", 3L, flash);

    assertEquals("redirect:/personal-inventory/blueprints", view);
    verify(backendApiClient).put(contains(id.toString()), any(), any());
    assertEquals(
        "personalInventory.blueprints.toast.noteUpdated",
        flash.getFlashAttributes().get("successToast"));
  }

  @Test
  void updateNote_mapsConflictToOptimisticLockToast() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.put(any(), any(), any()))
        .thenThrow(new BackendServiceException("conflict", null, 409));
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    controller.updateNote(id, "n", null, 1L, flash);

    assertEquals(
        "personalInventory.blueprints.error.conflict",
        flash.getFlashAttributes().get("errorToast"));
  }

  @Test
  void recipe_delegatesToBackendRecipeEndpoint() {
    UUID id = UUID.randomUUID();
    PersonalBlueprintRecipeDto dto =
        new PersonalBlueprintRecipeDto("Arclight Pistol", 2, List.of(), List.of());
    when(backendApiClient.get(
            contains("/api/v1/personal-blueprints/" + id + "/recipe"),
            eq(PersonalBlueprintRecipeDto.class)))
        .thenReturn(dto);

    PersonalBlueprintRecipeDto result = controller.recipe(id);

    assertEquals("Arclight Pistol", result.productName());
    assertEquals(2, result.variantCount());
  }

  @Test
  void recipe_returnsEmptyRecipe_whenBackendThrows() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.get(any(String.class), eq(PersonalBlueprintRecipeDto.class)))
        .thenThrow(new RuntimeException("boom"));

    PersonalBlueprintRecipeDto result = controller.recipe(id);

    assertNotNull(result);
    assertEquals(0, result.variantCount());
    assertTrue(result.requirementGroups().isEmpty());
  }

  @Test
  void delete_relaysDeleteAndFlashesSuccess() {
    UUID id = UUID.randomUUID();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String view = controller.delete(id, flash);

    assertEquals("redirect:/personal-inventory/blueprints", view);
    verify(backendApiClient).delete(contains(id.toString()), eq(Void.class));
    assertEquals(
        "personalInventory.blueprints.toast.removed",
        flash.getFlashAttributes().get("successToast"));
  }
}
