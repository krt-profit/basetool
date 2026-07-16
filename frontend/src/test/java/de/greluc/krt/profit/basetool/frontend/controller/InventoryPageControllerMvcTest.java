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

import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto;
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

  // covers REQ-INV-031 (design §6.2): the Einbuchen form renders the Material <-> Item catalog-mode
  // toggle and the remote-game-items picker marker, and the quality field stays confined to the
  // material block — the item block that follows carries the picker but no quality input.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_rendersCatalogModeToggleAndItemPickerWithoutQuality() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("name=\"inventoryCatalogMode\"")))
        .andExpect(content().string(containsString("data-testid=\"inventory-mode-material\"")))
        .andExpect(content().string(containsString("data-testid=\"inventory-mode-item\"")))
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-game-items\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"mode-material-fields\"",
                        "id=\"quality\"",
                        "id=\"mode-item-fields\"",
                        "id=\"gameItemId\"")));
  }

  // covers REQ-INV-031 (design §5.3/§6.6): the /inventory/item-search proxy behind the
  // remote-game-items combobox relays the term to the backend bookable-item catalog with the
  // token-carrying client and unwraps the page payload into the flat list the picker consumes.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_unwrapsBackendPageToFlatList() throws Exception {
    UUID itemId = UUID.randomUUID();
    PageResponse<InventoryGameItemReferenceDto> page =
        new PageResponse<>(
            List.of(new InventoryGameItemReferenceDto(itemId, "Quantum Drive", "RSI", "SHIP_ITEM")),
            0,
            50,
            1,
            1,
            Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/inventory/item-catalog?q=Quantum&size=50&sort=name,asc"), anyTypeRef()))
        .thenReturn(page);

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(itemId.toString()))
        .andExpect(jsonPath("$[0].name").value("Quantum Drive"));
  }

  // covers REQ-INV-031 (design §6.6): a backend failure degrades the item search to an empty list —
  // the combobox shows "no matches" instead of surfacing the error.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_backendFailure_returnsEmptyList() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
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
            null,
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
            null,
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
            null,
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
            matchingOrderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of());
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId,
            99,
            "h2",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(UUID.randomUUID()),
            List.of());

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
            null,
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
            null,
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
            new MaterialReferenceDto(materialId, "Laranite", "SCU"),
            null,
            7.0,
            80.0,
            80,
            List.of(stack));

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

  /** Builds the game-item reference used across the item-view render tests. */
  private static InventoryGameItemReferenceDto sampleGameItem(UUID gameItemId) {
    return new InventoryGameItemReferenceDto(
        gameItemId, "Quantum Drive XL-1", "RSI", "VEHICLE_ITEM");
  }

  // covers REQ-INV-030: the three Lager pages carry the Material <-> Items view switch as
  // server-rendered .tab-nav navigation links (view=items query parameter, no client toggling).
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersMaterialItemsViewSwitch() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"lager-view-material\"")))
        .andExpect(content().string(containsString("data-testid=\"lager-view-items\"")))
        .andExpect(content().string(containsString("/inventory/my?view=items")));
  }

  /**
   * Item-view render guard for the personal Lager (REQ-INV-030): {@code /inventory/my?view=items}
   * renders the game-item tree — group row with the gameItem name, kind badge and manufacturer, the
   * gameItemId stack key and a whole-unit amount — with no quality gauge and no mission filter,
   * while the item filters (gameItem multi-select fed only from stocked items, job orders, personal
   * flags) replace the material filter bar.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_itemsView_rendersItemTreeWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            false,
            null,
            3.0,
            null,
            null,
            2);
    GroupedInventoryDto group =
        new GroupedInventoryDto(null, sampleGameItem(gameItemId), 3.0, null, null, List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/grouped") && url.contains("catalog=ITEM")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-my"))
        // covers REQ-INV-030: item tree renders the gameItem group with its stack key.
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("RSI")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(containsString("lager-items-tree")))
        // No quality column in the item view: neither the gauge nor the min-quality filter.
        .andExpect(content().string(not(containsString("tree-gauge"))))
        .andExpect(content().string(not(containsString("id=\"minQuality\""))))
        // Item filters present: stocked-items multi-select + job orders + personal flags;
        // the mission filter does not exist for item rows.
        .andExpect(content().string(containsString("id=\"gameItemFilterContainer\"")))
        .andExpect(content().string(containsString("id=\"itemJobOrderFilterContainer\"")))
        .andExpect(content().string(containsString("id=\"itemPersonalOnly\"")))
        .andExpect(content().string(not(containsString("id=\"missionFilterContainer\""))))
        // Whole-unit amount ("Stück"), never a three-decimal SCU rendering.
        .andExpect(content().string(not(containsString("3.000"))));
  }

  /**
   * Item-view render guard for the squadron-wide Lager (REQ-INV-030): {@code
   * /inventory/all?view=items} renders the game-item tree with the per-owner stack key (userId in
   * addition to gameItemId) and the item filter bar, without quality or mission dimensions.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllInventory_itemsView_rendersItemTreeWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "owner", "Owner", "Logi Owner", null),
            new LocationReferenceDto(locationId, "Everus Harbor Storage"),
            null,
            false,
            null,
            5.0,
            null,
            null,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(null, sampleGameItem(gameItemId), 5.0, null, null, List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/grouped") && url.contains("catalog=ITEM")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-admin"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(containsString("data-user-id=\"" + userId + "\"")))
        .andExpect(content().string(containsString("lager-items-tree")))
        .andExpect(content().string(not(containsString("tree-gauge"))))
        .andExpect(content().string(not(containsString("id=\"minQuality\""))))
        .andExpect(content().string(containsString("id=\"gameItemFilterContainer\"")))
        .andExpect(content().string(not(containsString("id=\"missionFilterContainer\""))));
  }

  /**
   * Item-view render guard for the aggregated overview (REQ-INV-030): {@code /inventory?view=items}
   * renders per-item totals — name, manufacturer, localized kind, whole amount — without the
   * material view's quality columns, and its rows navigate to the org-wide item tree pre-filtered
   * to the clicked gameItem.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAggregatedInventory_itemsView_rendersItemColumnsWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    AggregatedInventoryDto row =
        new AggregatedInventoryDto(null, sampleGameItem(gameItemId), null, null, 7.0);
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(row), 0, 10, 1, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-index"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("RSI")))
        // Row click navigates into the org-wide item tree filtered to this gameItem.
        .andExpect(
            content()
                .string(containsString("/inventory/all?view=items&amp;gameItemIds=" + gameItemId)))
        // No quality columns in the item variant of the aggregated table.
        .andExpect(content().string(not(containsString("Max. Qualit"))));
  }

  /**
   * Drilldown render guard (REQ-INV-030): {@code /inventory/game-item/{gameItemId}} renders the
   * per-item stock rows (owner, location, whole amount) with the item's display name resolved from
   * the rows, no quality column and — deliberately — no navigate catalog picker (the item catalog
   * is thousands of entries; the remote picker ships with the Einbuchen pass).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewGameItemInventory_rendersDrilldownWithoutNavigatePicker() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(userId, "tester", "Tester", "Item Owner", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "Port Tressler"),
            null,
            4.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-03-01T00:00:00Z"));
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(item), 0, 1000, 1, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/game-item/" + gameItemId))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-game-item"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("Item Owner")))
        .andExpect(content().string(containsString("Port Tressler")))
        .andExpect(content().string(containsString("id=\"inventory-game-item-results\"")))
        .andExpect(content().string(containsString("/js/inventory-game-item.js")))
        .andExpect(content().string(not(containsString("id=\"materialSelect\""))))
        .andExpect(content().string(not(containsString("4.000"))));
  }

  /**
   * Item stack-entries fragment guard (REQ-INV-030/031): the lazy {@code
   * /inventory/my/game-item-stack/entries} drill-down renders the game-item leaf row — whole-unit
   * amount, PIECE-typed action buttons, no mission split, no Materialbörse toggle — and its "+
   * Zuordnen" picker offers only ITEM orders whose lines request the entry's gameItem
   * (requiredGameItemIds), never unrelated orders.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyGameItemStackEntries_rendersItemLeafWithoutMissionSplit() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID matchingOrderId = UUID.randomUUID();
    UUID unrelatedOrderId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-03-01T00:00:00Z"));
    JobOrderReferenceDto matching =
        new JobOrderReferenceDto(
            matchingOrderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(gameItemId));
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId,
            99,
            "h2",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(UUID.randomUUID()));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
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
            get("/inventory/my/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("locationId", locationId.toString())
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        // No mission dimension on item rows (REQ-INV-031) and no Börse toggle (design §8).
        .andExpect(content().string(not(containsString("data-assoc-field=\"MISSION\""))))
        .andExpect(content().string(not(containsString("inv-boerse-toggle"))))
        // PIECE-typed action buttons keyed on the gameItem, no materialId.
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(not(containsString("data-material-id"))))
        // Order picker gate: only the order requesting this gameItem is offered.
        .andExpect(content().string(containsString("value=\"" + matchingOrderId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + unrelatedOrderId + "\""))));
  }

  /**
   * Admin variant of the item stack-entries fragment (REQ-INV-030): {@code
   * /inventory/all/game-item-stack/entries} addresses the per-owner stack by gameItemId + userId
   * and renders the same quality-less, mission-less item leaf with the admin trigger set.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllGameItemStackEntries_rendersItemLeaf() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            Instant.parse("2026-03-01T00:00:00Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)
                  && url.contains("userId=" + userId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(not(containsString("data-assoc-field=\"MISSION\""))))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-bookout\"")))
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(not(containsString("data-material-id"))));
  }
}
