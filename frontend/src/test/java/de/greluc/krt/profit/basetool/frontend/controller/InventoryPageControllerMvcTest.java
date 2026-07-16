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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.controller.InventoryPageController.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionAllocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class InventoryPageControllerMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAggregatedInventory_AsMember_ShouldShowPage() throws Exception {
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(), 0, 10, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-index"))
        .andExpect(model().attributeExists("aggregated"))
        // REQ-INV-027: the aggregated Lager gained a "maximum quality" column between avg quality
        // and total quantity, so the table is now four columns wide (the empty-state row spans 4).
        .andExpect(content().string(containsString("colspan=\"4\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_AsMember_ShouldShowPage() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-admin"))
        .andExpect(model().attributeExists("groupedItems"));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void viewAllInventory_AsLogistician_ShouldShowActions() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Einbuchen")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_AsMember_ShouldNotShowActions() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Einbuchen"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_ShouldRenderBookOutAndUmbuchenControls() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    // REQ-INV-007 consolidation: the TRANSFER (Umbuchung) mode moved out of the Ausbuchen dialog
    // into the dedicated Umbuchen modal, so the book-out button only carries discard/sell labels
    // and
    // the Umbuchen modal is rendered alongside it.
    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bookOutSubmitBtn\"")))
        .andExpect(content().string(containsString("data-text-discard=\"Ausbuchen\"")))
        .andExpect(content().string(containsString("data-text-sell=\"Verkaufen\"")))
        .andExpect(content().string(not(containsString("data-text-transfer"))))
        .andExpect(content().string(containsString("id=\"umbuchenModal\"")))
        .andExpect(content().string(containsString("id=\"umbuchenSubmitBtn\"")))
        // Variante C (REQ-INV-027): the "Herkunft" (deduct-from) picker sections render in both the
        // Ausbuchen and Umbuchen modals, wired to their shared inventory-herkunft.js module.
        .andExpect(content().string(containsString("data-herkunft=\"bookout\"")))
        .andExpect(content().string(containsString("data-herkunft=\"umbuchen\"")))
        .andExpect(content().string(containsString("/js/inventory-herkunft.js")));
  }

  // REQ-INV-027: the personal Lager's Ausbuchen + Umbuchen modals carry the same "Herkunft"
  // (deduct-from) picker sections and load the shared inventory-herkunft.js module.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_ShouldRenderHerkunftPicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-herkunft=\"bookout\"")))
        .andExpect(content().string(containsString("data-herkunft=\"umbuchen\"")))
        .andExpect(content().string(containsString("data-herkunft-body")))
        .andExpect(content().string(containsString("/js/inventory-herkunft.js")));
  }

  // REQ-FE-016: the Umbuchen modal's target-location select opts into the searchable-combobox
  // enhancement on both Lager views — the marker must sit on the (statically attributed) select.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_umbuchenLocationPickerCarriesComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\" data-krt-combobox")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_umbuchenLocationPickerCarriesComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\" data-krt-combobox")));
  }

  // REQ-FE-016: the Einbuchen form's material AND location selects carry the combobox marker —
  // asserted in document order so each marker is pinned to its own select, not satisfied by the
  // page's remote-users user picker.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_materialAndLocationPickersCarryComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"materialId\"",
                        "data-krt-combobox",
                        "id=\"locationId\"",
                        "data-krt-combobox")));
  }

  // REQ-FE-016: the material drilldown's navigate select opts into the combobox enhancement (the
  // change-delegation reads data-trigger/data-url-template off the enhancer's hidden input). The
  // items fetch is stubbed as an empty page; the picker renders independently of the item list.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_navigateSelectCarriesComboboxMarker() throws Exception {
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 10, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/material/" + UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-material"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-url-template=\"/inventory/material/{value}\" data-krt-combobox")));
  }

  // covers REQ-INV-001 (SCU amount input) / REQ-INV-002 (PIECE amount input) — see
  // docs/specs/inv-material-quantities.md (render-wiring of the shared scu-decimal-input helper).
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_ShouldRenderScuDecimalAmountFieldsAndHelper() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        // The book-out amount/target fields are plain text+inputmode=decimal so they accept
        // either "." or "," regardless of browser locale; the data-scu-decimal marker opts them
        // into the shared normaliser.
        .andExpect(content().string(containsString("data-scu-decimal")))
        .andExpect(content().string(containsString("inputmode=\"decimal\"")))
        // The book-out target stock legitimately accepts 0, so it opts out of the > 0 rule.
        .andExpect(content().string(containsString("data-scu-allow-zero")))
        // The normaliser script, its defensive inline stub, and the localised positivity
        // messages are wired into every page's <head>.
        .andExpect(content().string(containsString("/js/scu-decimal-input.js")))
        .andExpect(content().string(containsString("window.krtScuInput")))
        .andExpect(content().string(containsString("window.krtScuI18n")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewAllInventory_ShouldRenderLocalStorageAttributes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"inventoryTable\"")))
        .andExpect(content().string(containsString("data-user-id=\"test-user-123\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_ShouldRenderLocalStorageAttributes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"inventoryTable\"")))
        .andExpect(content().string(containsString("data-user-id=\"test-user-123\"")))
        // The personal-entries-only filter checkbox renders in the stable filter bar.
        .andExpect(content().string(containsString("id=\"personalOnly\"")));
  }

  /**
   * Fragment-render guard for the personal Lager's lazy stack-entries drill-down ({@code
   * /inventory/my/stack/entries}). The append-only Lager loads a stack's entries on expand, not
   * inline, so this is where the per-entry Variante-C allocation chips (REQ-INV-027) live.
   * Regression: a refinery order assigned to a (now non-active) mission produces an entry whose
   * mission is no longer returned by {@code /api/v1/missions/lookup}; the mission must still appear
   * because its chip renders from the entry's own {@code missionAllocations}, independent of the
   * (empty) candidate lookup. Stubs the backend stack-entries page with that allocation and asserts
   * the real {@code stackEntriesMy} fragment carries the entry row (id) and the mission chip — so a
   * Thymeleaf 500 (stale {@code #{...}} key / bad SpEL) fails the build.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    String missionName = "Op Sundown (archived)";

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(new MissionAllocationDto(missionId, missionName, null, 4.0)),
            6.0,
            null,
            null,
            1L,
            Instant.parse("2026-02-03T10:15:30Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("missionId", missionId.toString())
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        // Variante C (REQ-INV-027): the mission is an allocation chip, not a scalar <option>.
        // The archived mission still shows because the chip renders from the entry's own
        // allocation, independent of whether the mission is still in the active lookup.
        .andExpect(content().string(containsString("assoc-chip--mission")))
        .andExpect(content().string(containsString("data-target-id=\"" + missionId + "\"")))
        .andExpect(content().string(containsString(missionName)));
  }

  /**
   * PIECE amounts render whole (REQ-INV-027): a {@code PIECE} material's allocation chip and rest
   * chip must show {@code 5} / {@code 10}, never {@code 5.000} / {@code 10.000}. Seeds a PIECE
   * entry (amount 10) with a job-order slice of 5 and asserts the rendered {@code stackEntriesMy}
   * fragment carries the order chip but no three-decimal amount anywhere — so a {@code
   * formatDecimal} regression on the chips / rest chip fails the build.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldRenderPieceAmountsWhole() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Titanium Bolt", "PIECE"),
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(new JobOrderAllocationDto(orderId, 42, 5.0)),
            5.0,
            java.util.List.of(),
            10.0,
            null,
            null,
            1L,
            Instant.parse("2026-02-03T10:15:30Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(containsString("assoc-chip--order")))
        // PIECE renders whole: neither the chip (5) nor the rest chips (5 / 10) show three
        // decimals.
        .andExpect(content().string(not(containsString("5.000"))))
        .andExpect(content().string(not(containsString("10.000"))));
  }

  /**
   * Picker-filter guard (REQ-ORDERS-018): the Lager "Auftrag" dropdown for a stack entry must offer
   * only orders whose requirements include the entry's material. This is the exact reported
   * regression — an ITEM order (no {@code job_order_material} rows, so an empty {@code materials}
   * list) was offered for every material; the filter now keys on {@code requiredMaterialIds}, which
   * is populated for both order kinds. Stubs two ITEM orders for the same lookup: one that requires
   * the entry's material (must render) and one that does not (must be hidden).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldOfferOnlyOrdersThatRequireTheEntryMaterial() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID matchingOrderId = UUID.randomUUID();
    UUID unrelatedOrderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-02-03T10:15:30Z"));

    // Both ITEM orders carry an empty MATERIAL-lines list; only requiredMaterialIds distinguishes
    // them (the ITEM-order case the old materials-based filter could not handle).
    JobOrderReferenceDto matching =
        new JobOrderReferenceDto(
            matchingOrderId, 71, "h1", "IN_PROGRESS", null, List.of(), List.of(materialId));
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId, 99, "h2", "IN_PROGRESS", null, List.of(), List.of(UUID.randomUUID()));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(matching, unrelated);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"" + matchingOrderId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + unrelatedOrderId + "\""))));
  }

  /**
   * Same as {@link #viewMyStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption()} for the
   * logistician/admin stack-entries drill-down ({@code /inventory/all/stack/entries} → {@code
   * stackEntriesAdmin} fragment), which additionally carries the owning {@code userId} in the stack
   * key. Since Variante C (REQ-INV-027) the mission is an editable allocation chip (gated behind
   * {@code sec:authorize} for association-capable roles), not a scalar {@code <option>}; the
   * archived mission still shows because its chip renders from the entry's own {@code
   * missionAllocations}, independent of the (empty) active-mission lookup.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    String missionName = "Op Sundown (archived)";

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(new MissionAllocationDto(missionId, missionName, null, 4.0)),
            6.0,
            null,
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/stack/entries")
                .param("materialId", materialId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("missionId", missionId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        // Variante C (REQ-INV-027): the mission is an editable allocation chip, not a scalar
        // <option>. The archived mission still shows because the chip renders from the entry's own
        // allocation, independent of whether the mission is still in the active lookup.
        .andExpect(content().string(containsString("assoc-chip--mission")))
        .andExpect(content().string(containsString("data-target-id=\"" + missionId + "\"")))
        .andExpect(content().string(containsString(missionName)));
  }

  /**
   * Full-render guard for the personal Lager's collapsed Material → Stack rows. The append-only
   * Lager no longer inlines a stack's entries, so this asserts the real {@code inventory-my} view
   * renders (HTTP 200) the collapsed stack row — its location, entry count, the toggle trigger and
   * the lazy {@code stack-entries-content} container ({@code data-stack-loaded="false"}) — while
   * NOT inlining any per-entry row (no {@code data-item-id}); the entries arrive via the separate
   * {@code /inventory/my/stack/entries} fragment (ADR-0003, REQ-INV-002). Catches a Thymeleaf 500
   * from the new stack-key {@code th:data-*} attributes or a stale {@code #{...}} key.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_WithStack_ShouldRenderCollapsedStackRow() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    String locationName = "Port Olisar Hangar 7";

    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new LocationReferenceDto(locationId, locationName),
            95,
            false,
            null,
            12.5,
            95.0,
            95,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            12.5,
            95.0,
            95,
            List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/grouped")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(locationName)))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-toggle-stack\"")))
        .andExpect(content().string(containsString("stack-entry-count")))
        .andExpect(content().string(containsString("data-stack-loaded=\"false\"")))
        .andExpect(content().string(containsString("stack-entries-content")))
        .andExpect(content().string(not(containsString("data-item-id="))));
  }

  /**
   * Full-render guard for the admin Lager's collapsed Material → Stack rows ({@code
   * inventory-admin.html}). Mirrors {@link
   * #viewMyInventory_WithStack_ShouldRenderCollapsedStackRow()} for {@code /inventory/all}: one
   * material → one stack. Asserts the real {@code inventory-admin} view renders (HTTP 200) the
   * collapsed stack row — its location, owner, entry count and the stack-toggle trigger plus the
   * lazy entries container — without inlining any per-entry row; entries load via {@code
   * /inventory/all/stack/entries}. Catches a render-500 from the new stack-key {@code th:data-*}
   * attributes or the {@code sec:authorize}-gated stack table.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllInventory_WithStack_ShouldRenderCollapsedStackRow() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    String locationName = "Everus Harbor Storage";
    String ownerName = "Logi Owner";

    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "owner", "Owner", ownerName, null),
            new LocationReferenceDto(locationId, locationName),
            80,
            false,
            null,
            7.0,
            80.0,
            80,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            new MaterialReferenceDto(materialId, "Laranite", "SCU"), 7.0, 80.0, 80, List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/grouped")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(locationName)))
        .andExpect(content().string(containsString(ownerName)))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-toggle-stack\"")))
        .andExpect(content().string(containsString("stack-entry-count")))
        .andExpect(content().string(containsString("data-stack-loaded=\"false\"")))
        .andExpect(content().string(containsString("stack-entries-content")));
  }

  /**
   * Graceful-degradation guard for the parallelized input-form catalog fan-out (#769): the lookups
   * run concurrently through the real {@link ParallelPageLoader}, but each fetch helper swallows
   * its own failure and returns an empty list, so {@code allOf(...).join()} must never propagate an
   * exception. Here the missions lookup throws while the materials lookup succeeds; the page must
   * still render {@code 200} with an empty {@code missions} model attribute and the populated
   * {@code materials} attribute — exactly as the serial version degraded.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_WhenOneCatalogFetchFails_StillRendersWithEmptyList() throws Exception {
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of(new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));
    when(backendApiClient.get(eq("/api/v1/missions/lookup"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(model().attribute("missions", empty()))
        .andExpect(model().attribute("materials", hasSize(1)));
  }

  // covers REQ-INV-027 (R4): the create form carries the Variante-C split-at-check-in allocation
  // sections + their hidden row templates that inventory-input.js clones.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_RendersSplitAtCheckInAllocationControls() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("id=\"jobOrderAllocRows\"")))
        .andExpect(content().string(containsString("id=\"missionAllocRows\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-input-add-order\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-input-add-mission\"")))
        .andExpect(content().string(containsString("id=\"jobOrderRowTemplate\"")));
  }
}
