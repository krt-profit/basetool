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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.*;
import de.greluc.krt.profit.basetool.frontend.model.form.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InventoryPageControllerTest {

  /**
   * Shared real {@link ParallelPageLoader} for the input-page tests. The loader runs each catalog
   * supplier on a virtual thread against the mocked {@link BackendApiClient}; a single static
   * instance avoids spinning up a fresh virtual-thread executor per test method.
   */
  private static final ParallelPageLoader PARALLEL = new ParallelPageLoader();

  private BackendApiClient backendApiClient;
  private InventoryPageController controller;
  private InventoryWriteController writeController;

  /** Mirrors {@code SecurityConfig#roleHierarchy}, which mirrors the backend's. */
  private static final org.springframework.security.access.hierarchicalroles.RoleHierarchy
      ROLE_HIERARCHY =
          org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl.fromHierarchy(
              """
              ROLE_ADMIN > ROLE_LOGISTICIAN
              ROLE_OFFICER > ROLE_LOGISTICIAN
              ROLE_ADMIN > ROLE_MISSION_MANAGER
              ROLE_OFFICER > ROLE_MISSION_MANAGER
              ROLE_ADMIN > ROLE_BANK_MANAGEMENT
              ROLE_BANK_MANAGEMENT > ROLE_BANK_EMPLOYEE
              """);

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new InventoryPageController(backendApiClient, PARALLEL, ROLE_HIERARCHY);
    writeController = new InventoryWriteController(backendApiClient, controller);
  }

  @Test
  void viewAggregatedInventory_shouldReturnIndexPage() {
    Model model = new ConcurrentModel();
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewAggregatedInventory(null, null, null, null, model);

    assertEquals("inventory-index", view);
    assertTrue(model.containsAttribute("aggregated"));
    assertTrue(model.containsAttribute("materials"));
  }

  @Test
  void viewAggregatedInventory_shouldHandleException() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenThrow(new RuntimeException("Backend error"));

    String view = controller.viewAggregatedInventory(null, null, null, null, model);

    assertEquals("inventory-index", view);
    assertEquals("error.inventory.aggregate.load", model.getAttribute("error"));
  }

  @Test
  void viewAggregatedInventory_fragmentResults_returnsResultsFragmentSelector() {
    Model model = new ConcurrentModel();
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewAggregatedInventory(null, null, null, "results", model);

    assertEquals("inventory-index :: inventoryResults", view);
  }

  @Test
  void viewMaterialInventory_shouldReturnMaterialPage() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 50, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewMaterialInventory(materialId, null, null, null, model);

    assertEquals("inventory-material", view);
    assertTrue(model.containsAttribute("items"));
    assertSame(page, model.getAttribute("inventoryMaterialPage"));
    assertEquals(List.of(50, 100, 200), model.getAttribute("pageSizes"));
    assertEquals(materialId, model.getAttribute("selectedMaterialId"));
  }

  @Test
  void viewMaterialInventory_forwardsPageAndWhitelistedSizeToBackend() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 3, 100, 350, 4, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    controller.viewMaterialInventory(materialId, 3, 100, null, model);

    verify(backendApiClient)
        .get(eq("/api/v1/inventory/material/" + materialId + "?page=3&size=100"), anyTypeRef());
  }

  @Test
  void viewMaterialInventory_snapsOutOfListSizeBackToDefault() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 50, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    controller.viewMaterialInventory(materialId, -1, 1000, null, model);

    verify(backendApiClient)
        .get(eq("/api/v1/inventory/material/" + materialId + "?page=0&size=50"), anyTypeRef());
  }

  @Test
  void viewMaterialInventory_withFragmentResults_returnsFragmentWithoutCatalogFetch() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 1, 50, 120, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewMaterialInventory(materialId, 1, null, "results", model);

    assertEquals("inventory-material :: inventoryMaterialResults", view);
    verify(backendApiClient, never()).getCached(any(), anyTypeRef());
    verify(backendApiClient, times(1)).get(anyString(), anyTypeRef());
  }

  @Test
  void viewGameItemInventory_shouldReturnItemPageAndForwardPaging() {
    Model model = new ConcurrentModel();
    UUID gameItemId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 2, 100, 250, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewGameItemInventory(gameItemId, 2, 100, null, model);

    assertEquals("inventory-game-item", view);
    assertTrue(model.containsAttribute("items"));
    assertSame(page, model.getAttribute("inventoryGameItemPage"));
    assertEquals(List.of(50, 100, 200), model.getAttribute("pageSizes"));
    assertEquals(gameItemId, model.getAttribute("selectedGameItemId"));
    verify(backendApiClient)
        .get(eq("/api/v1/inventory/game-item/" + gameItemId + "?page=2&size=100"), anyTypeRef());
  }

  @Test
  void viewGameItemInventory_snapsOutOfListSizeAndReturnsResultsFragment() {
    Model model = new ConcurrentModel();
    UUID gameItemId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 50, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view = controller.viewGameItemInventory(gameItemId, null, 1000, "results", model);

    assertEquals("inventory-game-item :: inventoryGameItemResults", view);
    verify(backendApiClient)
        .get(eq("/api/v1/inventory/game-item/" + gameItemId + "?page=0&size=50"), anyTypeRef());
  }

  @Test
  void viewMaterialInventory_clampsOutOfRangePageToLastPage() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    String base = "/api/v1/inventory/material/" + materialId;
    PageResponse<InventoryItemDto> overrun =
        new PageResponse<>(List.of(), 5, 50, 40, 1, Collections.emptyList());
    PageResponse<InventoryItemDto> lastPage =
        new PageResponse<>(List.of(), 0, 50, 40, 1, Collections.emptyList());
    when(backendApiClient.get(eq(base + "?page=5&size=50"), anyTypeRef())).thenReturn(overrun);
    when(backendApiClient.get(eq(base + "?page=0&size=50"), anyTypeRef())).thenReturn(lastPage);

    controller.viewMaterialInventory(materialId, 5, null, null, model);

    assertSame(lastPage, model.getAttribute("inventoryMaterialPage"));
    verify(backendApiClient).get(eq(base + "?page=5&size=50"), anyTypeRef());
    verify(backendApiClient).get(eq(base + "?page=0&size=50"), anyTypeRef());
  }

  @Test
  void viewMaterialInventory_doesNotClampAGenuinelyEmptyMaterial() {
    Model model = new ConcurrentModel();
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> empty =
        new PageResponse<>(List.of(), 0, 50, 0, 0, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);

    controller.viewMaterialInventory(materialId, 0, null, null, model);

    verify(backendApiClient, times(1)).get(anyString(), anyTypeRef());
  }

  @Test
  void viewGameItemInventory_clampsOverrunPageAndResolvesTitleFromLastPage() {
    Model model = new ConcurrentModel();
    UUID gameItemId = UUID.randomUUID();
    String base = "/api/v1/inventory/game-item/" + gameItemId;
    PageResponse<InventoryItemDto> overrun =
        new PageResponse<>(List.of(), 3, 50, 20, 1, Collections.emptyList());
    InventoryItemDto row =
        new InventoryItemDto(
            UUID.randomUUID(),
            null,
            null,
            new InventoryGameItemReferenceDto(gameItemId, "Quantum Drive", "RSI", "VEHICLE_ITEM"),
            null,
            null,
            4.0,
            false,
            List.of(),
            null,
            List.of(),
            null,
            null,
            null,
            1L,
            null,
            null);
    PageResponse<InventoryItemDto> lastPage =
        new PageResponse<>(List.of(row), 0, 50, 20, 1, Collections.emptyList());
    when(backendApiClient.get(eq(base + "?page=3&size=50"), anyTypeRef())).thenReturn(overrun);
    when(backendApiClient.get(eq(base + "?page=0&size=50"), anyTypeRef())).thenReturn(lastPage);

    controller.viewGameItemInventory(gameItemId, 3, null, null, model);

    assertSame(lastPage, model.getAttribute("inventoryGameItemPage"));
    assertEquals("Quantum Drive", model.getAttribute("gameItemName"));
  }

  @Test
  void viewMyInventory_shouldReturnMyInventoryPage() {
    Model model = new ConcurrentModel();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view =
        controller.viewMyInventory(
            null, null, null, null, null, null, null, false, false, false, model);

    assertEquals("inventory-my", view);
    assertTrue(model.containsAttribute("items"));
    assertTrue(model.containsAttribute("inventoryForm"));
  }

  @Test
  void viewMyInventory_shouldForwardMaterialAndMinQualityFiltersToBackend() {
    Model model = new ConcurrentModel();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    UUID materialId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();

    String view =
        controller.viewMyInventory(
            null,
            List.of(materialId),
            500,
            List.of(jobOrderId),
            null,
            null,
            null,
            false,
            false,
            false,
            model);

    assertEquals("inventory-my", view);
    assertEquals(List.of(materialId), model.getAttribute("selectedMaterialIds"));
    assertEquals(500, model.getAttribute("selectedMinQuality"));
    assertEquals(List.of(jobOrderId), model.getAttribute("selectedJobOrderIds"));
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(urlCaptor.capture(), anyTypeRef());
    String groupedUrl =
        urlCaptor.getAllValues().stream()
            .filter(u -> u.contains("/api/v1/inventory/my-inventory/grouped"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Personal grouped endpoint was not called"));
    assertTrue(groupedUrl.contains("materialIds=" + materialId), "materialIds must be forwarded");
    assertTrue(groupedUrl.contains("minQuality=500"), "minQuality must be forwarded");
    assertTrue(
        groupedUrl.contains("jobOrderIds=" + jobOrderId),
        "jobOrderIds must be forwarded alongside new filters");
  }

  @Test
  void viewMyInventory_personalOnly_forwardsFlagToBackendAndModel() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    String view =
        controller.viewMyInventory(
            null, null, null, null, null, null, null, true, false, false, model);

    assertEquals("inventory-my", view);
    assertEquals(true, model.getAttribute("selectedPersonalOnly"));
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(urlCaptor.capture(), anyTypeRef());
    String groupedUrl =
        urlCaptor.getAllValues().stream()
            .filter(u -> u.contains("/api/v1/inventory/my-inventory/grouped"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Personal grouped endpoint was not called"));
    assertTrue(
        groupedUrl.contains("personalOnly=true"), "personalOnly must be forwarded to the backend");
  }

  @Test
  void viewMyInventory_nonPersonalOnly_forwardsFlagToBackendAndModel() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    String view =
        controller.viewMyInventory(
            null, null, null, null, null, null, null, false, true, false, model);

    assertEquals("inventory-my", view);
    assertEquals(true, model.getAttribute("selectedNonPersonalOnly"));
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(urlCaptor.capture(), anyTypeRef());
    String groupedUrl =
        urlCaptor.getAllValues().stream()
            .filter(u -> u.contains("/api/v1/inventory/my-inventory/grouped"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Personal grouped endpoint was not called"));
    assertTrue(
        groupedUrl.contains("nonPersonalOnly=true"),
        "nonPersonalOnly must be forwarded to the backend");
  }

  @Test
  void viewMyInventory_shouldReturnFragmentWhenRequested() {
    Model model = new ConcurrentModel();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view =
        controller.viewMyInventory(
            null, null, null, null, null, null, null, false, false, true, model);

    assertEquals("inventory-my :: inventoryTableFragment", view);
  }

  @Test
  void myEntryIds_material_forwardsFiltersAndReturnsIds() {
    UUID materialId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    UUID entryA = UUID.randomUUID();
    UUID entryB = UUID.randomUUID();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of(entryA, entryB));

    List<UUID> ids =
        controller.myEntryIds(
            null, List.of(materialId), 500, List.of(jobOrderId), null, null, null, false, false);

    assertEquals(List.of(entryA, entryB), ids);
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient).get(urlCaptor.capture(), anyTypeRef());
    String url = urlCaptor.getValue();
    assertTrue(
        url.contains("/api/v1/inventory/my-inventory/entry-ids"),
        "the select-all proxy must hit the backend entry-ids endpoint");
    assertTrue(url.contains("materialIds=" + materialId), "materialIds must be forwarded");
    assertTrue(url.contains("minQuality=500"), "minQuality must be forwarded");
    assertTrue(url.contains("jobOrderIds=" + jobOrderId), "jobOrderIds must be forwarded");
    assertFalse(url.contains("catalog=ITEM"), "the material view must not relay catalog=ITEM");
  }

  @Test
  void myEntryIds_itemsView_relaysCatalogItemAndGameItemFilters() {
    UUID gameItemId = UUID.randomUUID();
    UUID entry = UUID.randomUUID();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of(entry));

    List<UUID> ids =
        controller.myEntryIds(
            "items", null, null, null, null, List.of(gameItemId), null, true, false);

    assertEquals(List.of(entry), ids);
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient).get(urlCaptor.capture(), anyTypeRef());
    String url = urlCaptor.getValue();
    assertTrue(url.contains("catalog=ITEM"), "the items view must relay catalog=ITEM");
    assertTrue(url.contains("gameItemIds=" + gameItemId), "gameItemIds must be forwarded");
    assertTrue(url.contains("personalOnly=true"), "personalOnly must be forwarded");
  }

  @Test
  void myEntryIds_nullBackendResult_returnsEmptyList() {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);

    assertEquals(
        List.of(), controller.myEntryIds(null, null, null, null, null, null, null, false, false));
  }

  @Test
  void viewAllInventory_shouldReturnAllInventoryPage() {
    Model model = new ConcurrentModel();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 1, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    String view =
        controller.viewAllInventory(
            null, List.of(UUID.randomUUID()), 100, null, null, null, null, false, model);

    assertEquals("inventory-admin", view);
    assertTrue(model.containsAttribute("items"));
  }

  @Test
  void viewInputPage_shouldReturnInputPage() {
    Model model = new ConcurrentModel();
    String view = controller.viewInputPage(null, model);
    assertEquals("inventory-input", view);
  }

  @Test
  void addInventoryItem_shouldRedirectOnSuccess() {
    InventoryForm form = new InventoryForm();
    form.setMaterialId(UUID.randomUUID());
    form.setLocationId(UUID.randomUUID());
    form.setQuality(100);
    form.setAmount(50.0);

    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(false);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    InventoryItemDto expectedDto =
        new InventoryItemDto(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            10,
            100.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            null);
    when(backendApiClient.post(anyString(), any(), eq(InventoryItemDto.class)))
        .thenReturn(expectedDto);

    String view =
        writeController.addInventoryItem(
            form, bindingResult, new ConcurrentModel(), redirectAttributes);

    assertEquals("redirect:/inventory", view);
    assertTrue(redirectAttributes.getFlashAttributes().containsKey("successToast"));
  }

  @Test
  void addInventoryItem_shouldHandleValidationError() {
    InventoryForm form = new InventoryForm();
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(true);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    String view =
        writeController.addInventoryItem(
            form, bindingResult, new ConcurrentModel(), redirectAttributes);

    assertEquals("inventory-input", view);
  }

  @Test
  void addInventoryItem_shouldHandleBackendException() {
    InventoryForm form = new InventoryForm();
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(false);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    when(backendApiClient.post(anyString(), any(), eq(InventoryItemDto.class)))
        .thenThrow(new RuntimeException("Error"));

    String view =
        writeController.addInventoryItem(
            form, bindingResult, new ConcurrentModel(), redirectAttributes);

    assertEquals("redirect:/inventory/input", view);
    assertTrue(redirectAttributes.getFlashAttributes().containsKey("errorToast"));
  }

  @Test
  void bookOutInventoryItem_shouldRedirectOnSuccess() {
    UUID id = UUID.randomUUID();
    de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm form =
        new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm();
    form.setAmount(10.0);
    form.setVersion(1L);
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(false);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    when(backendApiClient.post(anyString(), any(), eq(Void.class))).thenReturn(null);

    String view =
        writeController.bookOutInventoryItem(
            id, form, bindingResult, new ConcurrentModel(), redirectAttributes, "/inventory/all");

    assertEquals("redirect:/inventory/all", view);
    assertTrue(redirectAttributes.getFlashAttributes().containsKey("successToast"));
  }

  @Test
  void bookOutInventoryItem_shouldHandleBackendException() {
    UUID id = UUID.randomUUID();
    de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm form =
        new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm();
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(false);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    when(backendApiClient.post(anyString(), any(), eq(Void.class)))
        .thenThrow(new RuntimeException("Update error"));

    String view =
        writeController.bookOutInventoryItem(
            id, form, bindingResult, new ConcurrentModel(), redirectAttributes, "/inventory/all");

    assertEquals("redirect:/inventory/all", view);
    assertTrue(redirectAttributes.getFlashAttributes().containsKey("errorToast"));
  }

  @Test
  void bookOutInventoryItem_shouldPreserveFiltersFromRefererOnSuccess() {
    UUID id = UUID.randomUUID();
    de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm form =
        new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm();
    form.setAmount(5.0);
    form.setVersion(1L);
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(false);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    when(backendApiClient.post(anyString(), any(), eq(Void.class))).thenReturn(null);

    String referer =
        "https://example.org/inventory/my?materialIds=11111111-1111-1111-1111-111111111111&minQuality=50&jobOrderIds=22222222-2222-2222-2222-222222222222&fragment=true";
    String view =
        writeController.bookOutInventoryItem(
            id, form, bindingResult, new ConcurrentModel(), redirectAttributes, referer);

    assertEquals(
        "redirect:/inventory/my?materialIds=11111111-1111-1111-1111-111111111111&minQuality=50&jobOrderIds=22222222-2222-2222-2222-222222222222",
        view);
    assertTrue(redirectAttributes.getFlashAttributes().containsKey("successToast"));
  }

  @Test
  void bookOutInventoryItem_shouldPreserveFiltersForAdminView() {
    UUID id = UUID.randomUUID();
    de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm form =
        new de.greluc.krt.profit.basetool.frontend.model.form.InventoryBookOutForm();
    form.setAmount(5.0);
    form.setVersion(1L);
    BindingResult bindingResult = mock(BindingResult.class);
    when(bindingResult.hasErrors()).thenReturn(true);
    RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

    String referer =
        "https://example.org/inventory/all?missionIds=33333333-3333-3333-3333-333333333333&page=2";
    ConcurrentModel renderModel = new ConcurrentModel();
    String view =
        writeController.bookOutInventoryItem(
            id, form, bindingResult, renderModel, redirectAttributes, referer);

    assertEquals("inventory-admin", view);
    assertEquals("error.validation.failed", renderModel.getAttribute("errorToast"));
    assertEquals(id, renderModel.getAttribute("showBookOutModal"));
  }

  @Test
  void buildInventoryRedirectFromReferer_shouldHandleNullAndEmptyReferer() {
    assertEquals(
        "/inventory/my",
        de.greluc.krt.profit.basetool.frontend.controller.InventoryWriteController
            .buildInventoryRedirectFromReferer("/inventory/my", null));
    assertEquals(
        "/inventory/my",
        de.greluc.krt.profit.basetool.frontend.controller.InventoryWriteController
            .buildInventoryRedirectFromReferer("/inventory/my", ""));
    assertEquals(
        "/inventory/my",
        de.greluc.krt.profit.basetool.frontend.controller.InventoryWriteController
            .buildInventoryRedirectFromReferer(
                "/inventory/my", "https://example.org/inventory/my"));
    assertEquals(
        "/inventory/all",
        de.greluc.krt.profit.basetool.frontend.controller.InventoryWriteController
            .buildInventoryRedirectFromReferer(
                "/inventory/all", "https://example.org/inventory/all?fragment=true"));
  }

  @Test
  void updateInventoryItemNote_shouldReturnOkWithUpdatedDtoOnSuccess() {
    UUID id = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest("hello", 1L);
    InventoryItemDto updated =
        new InventoryItemDto(
            id,
            null,
            null,
            null,
            null,
            100,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            "hello",
            null,
            2L,
            null,
            null);
    when(backendApiClient.put(
            eq("/api/v1/inventory/" + id + "/note"), eq(request), eq(InventoryItemDto.class)))
        .thenReturn(updated);

    org.springframework.http.ResponseEntity<InventoryItemDto> response =
        writeController.updateInventoryItemNote(id, request);

    assertEquals(200, response.getStatusCode().value());
    assertSame(updated, response.getBody());
  }

  @Test
  void updateInventoryItemNote_shouldPropagate409FromBackendServiceException() {
    UUID id = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest("hello", 1L);
    de.greluc.krt.profit.basetool.frontend.service.BackendServiceException ex =
        new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
            "Backend service returned error: 409 CONFLICT", null, 409);
    when(backendApiClient.put(anyString(), any(), eq(InventoryItemDto.class))).thenThrow(ex);

    org.springframework.http.ResponseEntity<InventoryItemDto> response =
        writeController.updateInventoryItemNote(id, request);

    assertEquals(409, response.getStatusCode().value());
  }

  @Test
  void updateInventoryItemNote_shouldPropagate403FromBackendServiceException() {
    UUID id = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest("hello", 1L);
    de.greluc.krt.profit.basetool.frontend.service.BackendServiceException ex =
        new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
            "Backend service returned error: 403 FORBIDDEN", null, 403);
    when(backendApiClient.put(anyString(), any(), eq(InventoryItemDto.class))).thenThrow(ex);

    org.springframework.http.ResponseEntity<InventoryItemDto> response =
        writeController.updateInventoryItemNote(id, request);

    assertEquals(403, response.getStatusCode().value());
  }

  @Test
  void updateInventoryItemNote_shouldReturn500OnGenericException() {
    UUID id = UUID.randomUUID();
    InventoryItemNoteUpdateRequest request = new InventoryItemNoteUpdateRequest("hello", 1L);
    when(backendApiClient.put(anyString(), any(), eq(InventoryItemDto.class)))
        .thenThrow(new RuntimeException("boom"));

    org.springframework.http.ResponseEntity<InventoryItemDto> response =
        writeController.updateInventoryItemNote(id, request);

    assertEquals(500, response.getStatusCode().value());
  }

  @Test
  void transferInventoryItem_fullyConsumed_returns204() {
    UUID id = UUID.randomUUID();
    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            10.0,
            UUID.randomUUID(),
            null,
            CheckoutType.TRANSFER,
            null,
            null,
            1L,
            null,
            null,
            null,
            null);
    when(backendApiClient.post(
            eq("/api/v1/inventory/" + id + "/book-out"), eq(dto), eq(InventoryItemDto.class)))
        .thenReturn(null);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.transferInventoryItem(id, dto);

    assertEquals(204, response.getStatusCode().value());
    assertNull(response.getBody());
  }

  @Test
  void transferInventoryItem_notFullyConsumed_returns200WithBody() {
    UUID id = UUID.randomUUID();
    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            5.0,
            UUID.randomUUID(),
            null,
            CheckoutType.TRANSFER,
            null,
            null,
            1L,
            null,
            null,
            null,
            null);
    InventoryItemDto remaining =
        new InventoryItemDto(
            id,
            null,
            null,
            null,
            null,
            100,
            45.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            2L,
            null,
            null);
    when(backendApiClient.post(
            eq("/api/v1/inventory/" + id + "/book-out"), eq(dto), eq(InventoryItemDto.class)))
        .thenReturn(remaining);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.transferInventoryItem(id, dto);

    assertEquals(200, response.getStatusCode().value());
    assertSame(remaining, response.getBody());
  }

  @Test
  void transferInventoryItem_conflict_propagatesProblemJsonWithCode() {
    UUID id = UUID.randomUUID();
    InventoryItemBookOutDto dto =
        new InventoryItemBookOutDto(
            10.0,
            UUID.randomUUID(),
            null,
            CheckoutType.TRANSFER,
            null,
            null,
            1L,
            null,
            null,
            null,
            null);
    de.greluc.krt.profit.basetool.frontend.service.BackendServiceException ex =
        new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
            "Backend returned 409 [OPTIMISTIC_LOCK]",
            null,
            409,
            "OPTIMISTIC_LOCK",
            null,
            java.util.List.of(),
            null);
    when(backendApiClient.post(anyString(), any(), eq(InventoryItemDto.class))).thenThrow(ex);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.transferInventoryItem(id, dto);

    assertEquals(409, response.getStatusCode().value());
    assertEquals(
        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON,
        response.getHeaders().getContentType());
    java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
    assertNotNull(body);
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void updateDelivered_success_returnsUpdatedDtoWithBumpedVersion() {
    UUID id = UUID.randomUUID();
    UpdateDeliveredRequest request = new UpdateDeliveredRequest(true, UUID.randomUUID(), 1L);
    InventoryItemDto updated =
        new InventoryItemDto(
            id,
            null,
            null,
            null,
            null,
            100,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            2L,
            null,
            null);
    when(backendApiClient.patch(
            eq("/api/v1/inventory/" + id + "/delivered"), eq(request), eq(InventoryItemDto.class)))
        .thenReturn(updated);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.updateDelivered(id, request);

    assertEquals(200, response.getStatusCode().value());
    assertSame(updated, response.getBody());
    assertEquals(Long.valueOf(2L), ((InventoryItemDto) response.getBody()).version());
  }

  @Test
  void updateDelivered_conflict_propagatesProblemJsonWithCode() {
    UUID id = UUID.randomUUID();
    UpdateDeliveredRequest request = new UpdateDeliveredRequest(true, UUID.randomUUID(), 1L);
    de.greluc.krt.profit.basetool.frontend.service.BackendServiceException ex =
        new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
            "Backend returned 409 [OPTIMISTIC_LOCK]",
            null,
            409,
            "OPTIMISTIC_LOCK",
            null,
            java.util.List.of(),
            null);
    when(backendApiClient.patch(anyString(), any(), eq(InventoryItemDto.class))).thenThrow(ex);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.updateDelivered(id, request);

    assertEquals(409, response.getStatusCode().value());
    assertEquals(
        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON,
        response.getHeaders().getContentType());
    java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
    assertNotNull(body);
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  @Test
  void rebookPersonalInventoryItem_success_returnsNewRow() {
    UUID id = UUID.randomUUID();
    InventoryItemPersonalRebookDto dto = new InventoryItemPersonalRebookDto(5.0, 1L, null, null);
    InventoryItemDto newRow =
        new InventoryItemDto(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            100,
            5.0,
            true,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            null);
    when(backendApiClient.post(
            eq("/api/v1/inventory/" + id + "/personal-rebook"),
            eq(dto),
            eq(InventoryItemDto.class)))
        .thenReturn(newRow);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.rebookPersonalInventoryItem(id, dto);

    assertEquals(200, response.getStatusCode().value());
    assertSame(newRow, response.getBody());
    verify(backendApiClient)
        .post(
            eq("/api/v1/inventory/" + id + "/personal-rebook"),
            eq(dto),
            eq(InventoryItemDto.class));
  }

  @Test
  void rebookPersonalInventoryItem_conflict_propagatesProblemJson() {
    UUID id = UUID.randomUUID();
    InventoryItemPersonalRebookDto dto = new InventoryItemPersonalRebookDto(5.0, 1L, null, null);
    de.greluc.krt.profit.basetool.frontend.service.BackendServiceException ex =
        new de.greluc.krt.profit.basetool.frontend.service.BackendServiceException(
            "Backend returned 409 [OPTIMISTIC_LOCK]",
            null,
            409,
            "OPTIMISTIC_LOCK",
            null,
            java.util.List.of(),
            null);
    when(backendApiClient.post(anyString(), any(), eq(InventoryItemDto.class))).thenThrow(ex);

    org.springframework.http.ResponseEntity<Object> response =
        writeController.rebookPersonalInventoryItem(id, dto);

    assertEquals(409, response.getStatusCode().value());
    assertEquals(
        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON,
        response.getHeaders().getContentType());
    java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
    assertNotNull(body);
    assertEquals("OPTIMISTIC_LOCK", body.get("code"));
  }

  /**
   * REQ-INV-040: the location filter reaches the backend on the material view of "Mein Lager", is
   * echoed into the model for the checked state, and its options are derived from the grouped
   * result's stacks rather than from a location catalog lookup.
   */
  @Test
  void viewMyInventory_locationFilter_forwardedToBackendAndOptionsComeFromTheStacks() {
    Model model = new ConcurrentModel();
    UUID locationId = UUID.randomUUID();
    LocationReferenceDto location = new LocationReferenceDto(locationId, "Everus Harbor");
    InventoryStackDto stack =
        new InventoryStackDto(null, location, 700, false, null, 10.0, 700.0, 700, 1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(null, null, 10.0, 700.0, 700, List.of(stack));
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of(group));

    String view =
        controller.viewMyInventory(
            null, null, null, null, null, null, List.of(locationId), false, false, false, model);

    assertEquals("inventory-my", view);
    assertEquals(List.of(locationId), model.getAttribute("selectedLocationIds"));
    assertEquals(List.of(location), model.getAttribute("locations"));
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(urlCaptor.capture(), anyTypeRef());
    String groupedUrl =
        urlCaptor.getAllValues().stream()
            .filter(u -> u.contains("/api/v1/inventory/my-inventory/grouped"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Personal grouped endpoint was not called"));
    assertTrue(groupedUrl.contains("locationIds=" + locationId), "locationIds must be forwarded");
  }

  /**
   * REQ-INV-040: the options must survive an active location filter. Deriving them from the
   * filtered result would leave the dropdown holding only the locations already picked, so the user
   * could never widen the selection again — the controller therefore re-reads the view unfiltered
   * and the option list keeps the location that the filter excluded.
   */
  @Test
  void viewMyInventory_locationOptions_areNotNarrowedByTheActiveLocationFilter() {
    Model model = new ConcurrentModel();
    UUID pickedId = UUID.randomUUID();
    LocationReferenceDto picked = new LocationReferenceDto(pickedId, "Everus Harbor");
    LocationReferenceDto other = new LocationReferenceDto(UUID.randomUUID(), "Area18");
    GroupedInventoryDto filtered =
        new GroupedInventoryDto(
            null,
            null,
            10.0,
            700.0,
            700,
            List.of(new InventoryStackDto(null, picked, 700, false, null, 10.0, 700.0, 700, 1)));
    GroupedInventoryDto unfiltered =
        new GroupedInventoryDto(
            null,
            null,
            30.0,
            700.0,
            700,
            List.of(
                new InventoryStackDto(null, picked, 700, false, null, 10.0, 700.0, 700, 1),
                new InventoryStackDto(null, other, 700, false, null, 20.0, 700.0, 700, 1)));
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            invocation -> {
              String url = invocation.getArgument(0);
              if (url.contains("/api/v1/inventory/my-inventory/grouped")) {
                return url.contains("locationIds=") ? List.of(filtered) : List.of(unfiltered);
              }
              return List.of();
            });

    controller.viewMyInventory(
        null, null, null, null, null, null, List.of(pickedId), false, false, false, model);

    assertEquals(List.of(other, picked), model.getAttribute("locations"));
  }

  /** REQ-INV-040: the shared "Globales Lager" relays the same filter. */
  @Test
  void viewAllInventory_locationFilter_forwardedToBackendAndModel() {
    Model model = new ConcurrentModel();
    UUID locationId = UUID.randomUUID();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    String view =
        controller.viewAllInventory(
            null, null, null, null, null, null, List.of(locationId), false, model);

    assertEquals("inventory-admin", view);
    assertEquals(List.of(locationId), model.getAttribute("selectedLocationIds"));
    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.atLeastOnce())
        .get(urlCaptor.capture(), anyTypeRef());
    String groupedUrl =
        urlCaptor.getAllValues().stream()
            .filter(u -> u.contains("/api/v1/inventory/all/grouped"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Global grouped endpoint was not called"));
    assertTrue(groupedUrl.contains("locationIds=" + locationId), "locationIds must be forwarded");
  }

  /**
   * REQ-INV-040 + REQ-INV-034: "Alle markieren" resolves the same set the filtered table shows, so
   * the select-all proxy relays the location filter on both views. Without this the bulk selection
   * would reach past the location the user is looking at.
   */
  @Test
  void myEntryIds_locationFilter_forwardedOnBothViews() {
    UUID locationId = UUID.randomUUID();
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    controller.myEntryIds(null, null, null, null, null, null, List.of(locationId), false, false);
    controller.myEntryIds("items", null, null, null, null, null, List.of(locationId), false, false);

    org.mockito.ArgumentCaptor<String> urlCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(backendApiClient, org.mockito.Mockito.times(2))
        .get(urlCaptor.capture(), anyTypeRef());
    assertTrue(
        urlCaptor.getAllValues().stream()
            .allMatch(
                u ->
                    u.contains("/api/v1/inventory/my-inventory/entry-ids")
                        && u.contains("locationIds=" + locationId)),
        "both views must relay locationIds to the select-all endpoint");
  }
}
