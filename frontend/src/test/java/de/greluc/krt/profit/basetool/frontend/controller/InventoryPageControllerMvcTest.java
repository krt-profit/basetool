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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionAllocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  // REQ-INV-027: the prefill note for a determined dimension (single tag, no rest) is rendered from
  // the `auto` entry of the page's herkunftI18n bootstrap. The module carries an English fallback
  // for it, so a page that forgot to declare the key would degrade silently in German -- assert the
  // localized string reaches both Lager pages instead. Only the ASCII prefix is pinned: Thymeleaf's
  // JavaScript inlining emits the umlaut as a \\u00FC escape inside the string literal.
  @ParameterizedTest
  @ValueSource(strings = {"/inventory/my", "/inventory/all"})
  @WithMockUser(roles = "KRT_MEMBER")
  void inventoryPages_ShouldBootstrapHerkunftAutoPrefillLabel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auto: \"Automatisch vorbef")));
  }

  /**
   * REQ-INV-037: the filter row sits in a collapsible panel, in both the Material and the Items
   * view. Four separate guarantees are pinned here because each fails silently on its own:
   *
   * <ul>
   *   <li>the toggle carries its delegated {@code data-trigger} and the {@code
   *       aria-expanded}/{@code aria-controls} pair — without them the collapse is a dead button
   *       and mute to a screen reader;
   *   <li>the panel is rendered EXPANDED. {@code hidden} is the collapse mechanism and the script
   *       applies it on load, so a server-rendered collapsed panel would leave a client without
   *       JavaScript no way to reach the filters at all;
   *   <li>the filter form is INSIDE the panel, between the toggle and the bulk bar — a form left
   *       outside stays permanently visible and the collapse silently does nothing;
   *   <li>the count chip ships the raw {@code {0}} placeholder. The script substitutes the number
   *       client-side, so a message source that resolved the argument here would hand it a string
   *       with nothing left to replace and the count would never be announced.
   * </ul>
   *
   * @param path the Lager view under test — the two views render two different filter forms
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"/inventory/my", "/inventory/my?view=items"})
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersTheFilterRowInsideACollapsiblePanel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-trigger=\"inv-my-toggle-filters\"")))
        .andExpect(content().string(containsString("aria-controls=\"myFilterPanel\"")))
        .andExpect(content().string(containsString("aria-expanded=\"true\"")))
        .andExpect(
            content()
                .string(containsString("<div class=\"inv-filter-panel\" id=\"myFilterPanel\">")))
        .andExpect(content().string(containsString("data-label=\"Aktive Filter: {0}\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of(
                            "id=\"myFilterToggle\"",
                            "id=\"myFilterPanel\"",
                            "my-inventory-filter",
                            "id=\"bulkCheckoutBar\""))));
  }

  /**
   * REQ-INV-037 on the shared "Globales Lager", pinning the same four guarantees as its "Mein
   * Lager" twin above — the delegated {@code data-trigger} plus the {@code aria-expanded}/{@code
   * aria-controls} pair, the panel rendered EXPANDED so a client without JavaScript keeps its
   * filters, the filter form INSIDE the panel, and the raw {@code {0}} placeholder the script
   * substitutes client-side.
   *
   * <p>The source-order assertion is what catches the specific way this page can regress: its two
   * filter forms used to sit in a wrapper INSIDE the action bar, so a panel opened around the
   * wrapper rather than around the forms would still render every attribute asserted here while
   * collapsing nothing at all.
   *
   * @param path the Lager view under test — the two views render two different filter forms
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"/inventory/all", "/inventory/all?view=items"})
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_rendersTheFilterRowInsideACollapsiblePanel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-toggle-filters\"")))
        .andExpect(content().string(containsString("aria-controls=\"globalFilterPanel\"")))
        .andExpect(content().string(containsString("aria-expanded=\"true\"")))
        .andExpect(
            content()
                .string(
                    containsString("<div class=\"inv-filter-panel\" id=\"globalFilterPanel\">")))
        .andExpect(content().string(containsString("data-label=\"Aktive Filter: {0}\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of(
                            "id=\"globalFilterToggle\"",
                            "id=\"globalFilterPanel\"",
                            "global-inventory-filter",
                            "id=\"tableContainer\""))));
  }

  // REQ-INV-034: the "Alle markieren" (select-all) button renders in the bulk bar BEFORE the
  // "Markierte ausbuchen" button, carries the select-all trigger + both toggle labels, and the
  // entry-ids proxy is wired for the JS to fetch the full filtered id set.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersSelectAllButtonBeforeBulkCheckout() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bulkSelectAllBtn\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-select-all\"")))
        .andExpect(content().string(containsString("data-text-select")))
        .andExpect(content().string(containsString("data-text-clear")))
        // The select-all button must sit before the bulk-checkout button in the bar.
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of("id=\"bulkSelectAllBtn\"", "id=\"bulkCheckoutBtn\""))));
  }

  // REQ-INV-036: the Massen-Umbuchen action renders in the same bulk bar, after "Markierte
  // ausbuchen", and its modal offers all three modes — LOCATION plus BOTH personal directions,
  // which a bulk selection needs because it can mix personal and shared stock (the single-row
  // modal, by contrast, infers one direction from the source row).
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersBulkRebookButtonAndModalWithAllThreeModes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bulkRebookBtn\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-open-bulk-rebook\"")))
        .andExpect(content().string(containsString("id=\"bulkRebookModal\"")))
        .andExpect(content().string(containsString("id=\"bulkRebookForm\"")))
        .andExpect(content().string(containsString("value=\"PERSONALIZE\"")))
        .andExpect(content().string(containsString("value=\"DEPERSONALIZE\"")))
        // The bulk-rebook button follows the bulk-checkout button in the bar.
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of("id=\"bulkCheckoutBtn\"", "id=\"bulkRebookBtn\""))));
  }

  // REQ-FE-016: the Umbuchen modal's target-location select is a server-side-search combobox
  // (remote-locations) on both Lager views — the marker value must sit on the (statically
  // attributed) select, which renders EMPTY (no preloaded catalog options; the modal-opening JS
  // seeds the row's current location).
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
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\""
                            + " data-krt-combobox=\"remote-locations\"></select>")));
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
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\""
                            + " data-krt-combobox=\"remote-locations\"></select>")));
  }

  // REQ-INV-026: the Umbuchen modal's merge-stock opt-in renders in the shared .check-row layout
  // (checkbox left, explicit-for label + .form-hint help stacked right) and starts hidden — the
  // page JS reveals it per-open for SCU rows only. The krtm-hidden class must sit on the row so the
  // .check-row.krtm-hidden display override keeps working.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_umbuchenMergeRowUsesSharedCheckRowLayout() throws Exception {
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
                        "id=\"umbuchenMergeRow\" class=\"form-group check-row mb-1 krtm-hidden\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"umbuchenMergeRow\"",
                        "id=\"umbuchenMergeStock\"",
                        "for=\"umbuchenMergeStock\"",
                        "class=\"form-hint\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_umbuchenMergeRowUsesSharedCheckRowLayout() throws Exception {
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
                        "id=\"umbuchenMergeRow\" class=\"form-group check-row mb-1 krtm-hidden\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"umbuchenMergeRow\"",
                        "id=\"umbuchenMergeStock\"",
                        "for=\"umbuchenMergeStock\"",
                        "class=\"form-hint\"")));
  }

  // REQ-INV-026: the Einbuchen form's personal-entry and merge-stock checkbox rows both use the
  // shared .check-row layout, in document order personal row (visible) before merge row (hidden
  // until inventory-input.js reveals it for an SCU material).
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_personalAndMergeRowsUseSharedCheckRowLayout() throws Exception {
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
                        "class=\"form-group check-row\"",
                        "id=\"personal\"",
                        "for=\"personal\"",
                        "class=\"form-hint\"",
                        "class=\"form-group check-row krtm-hidden\" id=\"merge-stock-row\"",
                        "id=\"mergeStock\"",
                        "for=\"mergeStock\"",
                        "class=\"form-hint\"")));
  }

  // REQ-FE-016: the Einbuchen form's material AND location selects are server-side-search
  // comboboxes (remote-materials / remote-locations) — the marker values are asserted in document
  // order so each is pinned to its own select, not satisfied by the page's remote-users user
  // picker. The full material catalog must no longer be dumped into the page: with no preselected
  // form value only the placeholder renders, so a stubbed catalog material's name stays absent.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_materialAndLocationPickersCarryComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(
            List.of(
                new MaterialReferenceDto(UUID.randomUUID(), "Quantanium", "SCU"),
                new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
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
                        "data-krt-combobox=\"remote-materials\"",
                        "id=\"locationId\"",
                        "data-krt-combobox=\"remote-locations\"")))
        .andExpect(content().string(not(containsString("Quantanium"))))
        .andExpect(content().string(not(containsString("Laranite"))));
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

  // The Einbuchen form's two opt-in checkboxes (personal entry + REQ-INV-026 stock merge) must
  // share ONE row format: both render as a `form-group check-row` (checkbox left, label + muted
  // form-hint stacked right) instead of the former ad-hoc single-line flex rows whose long merge
  // label wrapped around the checkbox. Asserted in document order so each class match is pinned
  // to its own row.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_checkboxRowsShareCheckRowFormat() throws Exception {
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
                        "class=\"form-group check-row\"",
                        "id=\"personal\"",
                        "class=\"form-hint\"",
                        "class=\"form-group check-row krtm-hidden\" id=\"merge-stock-row\"",
                        "id=\"mergeStock\"",
                        "class=\"form-hint\"")));
  }

  // Two page-CSS guards for the Einbuchen form: (1) the blanket `.form-group input` rule excludes
  // radio/checkbox inputs via a zero-specificity :where(), so the Material <-> Item radios keep
  // the global 1.2rem KRT circle styling and the rule cannot outrank the combobox chevron
  // padding; (2) the (0,3,0) `.form-group.check-row.krtm-hidden` override must exist — the
  // (0,2,0) check-row flex rule would otherwise beat the (0,1,0) `.krtm-hidden` utility and the
  // REQ-INV-026 merge opt-in row could never be hidden for PIECE materials.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_pageCssExcludesTogglesAndKeepsMergeRowHideable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        ".form-group input:where(:not([type='checkbox']):not([type='radio']))")))
        .andExpect(content().string(containsString(".form-group.check-row.krtm-hidden")));
  }

  // REQ-FE-011/REQ-FE-016: the shared combobox i18n bootstrap (fragments/head.html) must carry a
  // per-source `kinds` entry for EVERY registered remote-source marker, so a material/location/
  // item/account picker greets the user with its own placeholder instead of the user-picker
  // wording. (Set-parity with the JS registries is separately gated by ComboboxKindsParityTest.)
  // The two German material/location placeholders are asserted by prefix (the umlaut tail is
  // unicode-escaped by the Thymeleaf JS serializer); German is pinned via the KRT_LOCALE cookie —
  // the CookieLocaleResolver ignores Accept-Language once a default locale is set.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_comboboxKindsMapCoversEveryRemoteSource() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input").cookie(new Cookie("KRT_LOCALE", "de")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "kinds: {",
                        "'remote-users':",
                        "'remote-bank-users':",
                        "'remote-materials':",
                        "'remote-materials-joborder':",
                        "'remote-materials-raw':",
                        "'remote-locations':",
                        "'remote-game-items':",
                        "'remote-bank-accounts':")))
        .andExpect(content().string(containsString("Material suchen oder w")))
        .andExpect(content().string(containsString("Ort suchen oder w")));
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
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum")))
        .thenReturn(page);

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(itemId.toString()))
        .andExpect(jsonPath("$[0].name").value("Quantum Drive"));
  }

  // #1344 regression: a multi-word item name must reach the backend single-encoded (the real
  // spaces), not double-encoded (%2520). The term rides as a URI variable ({q}) and is verified
  // to be forwarded verbatim, so the combobox finds e.g. "Quantum Drive" again.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_passesMultiWordQueryAsUriVariable() throws Exception {
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
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum Drive")))
        .thenReturn(page);

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum Drive"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Quantum Drive"));

    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum Drive"));
  }

  // covers REQ-INV-031 (design §6.6): a backend failure degrades the item search to an empty list —
  // the combobox shows "no matches" instead of surfacing the error.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_backendFailure_returnsEmptyList() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  // REQ-FE-016: the material drilldown's navigate select is a server-side-search combobox
  // (remote-materials); the change-delegation reads data-trigger/data-url-template off the
  // enhancer's hidden input. Only the currently-viewed material is seeded as an <option> — the
  // rest of the catalog is fetched on demand, so a second stubbed material's name stays absent.
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_navigateSelectCarriesComboboxMarker() throws Exception {
    UUID selectedMaterialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 10, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(
            List.of(
                new MaterialReferenceDto(selectedMaterialId, "Quantanium", "SCU"),
                new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));

    mockMvc
        .perform(get("/inventory/material/" + selectedMaterialId))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-material"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-url-template=\"/inventory/material/{value}\""
                            + " data-krt-combobox=\"remote-materials\"")))
        .andExpect(content().string(containsString("Quantanium")))
        .andExpect(content().string(not(containsString("Laranite"))));
  }

  /**
   * Full-render guard for the per-material drilldown's server-side pagination (REQ-INV-033). Stubs
   * a three-page backend response and asserts the real {@code inventory-material} view renders a
   * data row plus the pager (a next-page link at the snapped size) and the size picker's
   * whitelisted options — so the page can never again silently cap a large material at a single
   * fetch (ADR-0104), and a Thymeleaf error in the pager wiring fails the build.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_ShouldRenderPaginationControls() throws Exception {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(UUID.randomUUID(), "user", "User", "User", 1),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"),
            80,
            10.0,
            false,
            List.of(),
            10.0,
            List.of(),
            10.0,
            null,
            null,
            1L,
            Instant.now());
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(item), 1, 50, 120, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(List.of(new MaterialReferenceDto(materialId, "Quantanium", "SCU")));

    mockMvc
        .perform(get("/inventory/material/" + materialId).param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-material"))
        .andExpect(content().string(containsString("Port Olisar")))
        // The pager renders inside the results fragment with a real next-page link at the
        // whitelisted size, plus the size-picker options.
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(containsString("size=50")))
        .andExpect(content().string(containsString(">200<")));
  }

  /**
   * Fragment-swap guard for the material drilldown pager (REQ-INV-033 / REQ-FE-005): a {@code
   * fragment=results} request renders only the results table + pager — no full page, no material
   * switcher — and must not re-fetch the cached material catalog (the REQ-DATA-012 fragment-gating
   * rule, so in-place paging does not amplify catalog reads).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_FragmentSwap_RendersPagerWithoutCatalogFetch() throws Exception {
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 1, 50, 120, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(
            get("/inventory/material/" + materialId)
                .param("page", "1")
                .param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(not(containsString("materialSelect"))));

    // Scoped to the materials catalog: the layout advices legitimately read other cached
    // catalogs (title, capabilities) on every request, fragment or not.
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef());
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
            List.of(),
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
            List.of(),
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
   * Book-in-form picker-filter guard (REQ-ORDERS-018): the {@code /inventory/input} order dropdown
   * carries a per-option {@code data-materials} CSV that the client filter ({@code
   * inventory-input.js#filterOrderSelects}) keys on. It must be the order's kind-agnostic {@code
   * requiredMaterialIds}, not its {@code materials} MATERIAL-lines — an ITEM (crafting) order has
   * no {@code job_order_material} rows, so an empty {@code materials} list rendered {@code
   * data-materials=""} and the filter silently hid every ITEM order whose blueprint consumes the
   * picked material (the reported "no/all/some orders" unreliability). Stubs an ITEM order with an
   * empty {@code materials} list but a populated {@code requiredMaterialIds}, and asserts the
   * option exposes the required material id in {@code data-materials}.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_ShouldKeyOrderDataMaterialsOnRequiredMaterialIds() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID itemOrderId = UUID.randomUUID();

    // An ITEM order: empty MATERIAL-lines list, so only requiredMaterialIds surfaces the material
    // its blueprint consumes — exactly the case the old order.materials-based CSV dropped.
    JobOrderReferenceDto itemOrder =
        new JobOrderReferenceDto(
            itemOrderId,
            71,
            "craft-1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/orders/lookup")) {
                return List.of(itemOrder);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        // The ITEM order's option must tie its value to a data-materials CSV holding the required
        // material id; the old order.materials source rendered data-materials="" here.
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "value=\"" + itemOrderId + "\"", "data-materials=\"" + materialId + "\"")))
        .andExpect(content().string(not(containsString("data-materials=\"\""))));
  }

  /**
   * Check-in picker need figures (REQ-INV-039, #1740): the {@code /inventory/input} form embeds
   * each order's outstanding per-material need as one JSON blob on the allocation group, which
   * {@code inventory-input.js} decodes to label the options.
   *
   * <p>Also pins that the page asks the lookup for the figures at all: without {@code
   * withNeeds=true} the backend ships an empty list and every option would render unlabelled — a
   * failure with no error anywhere.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_ShouldEmbedTheOutstandingNeedFigures() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, 650, 400.0, 150.0, 250.0)));

    java.util.List<String> lookupUrls = new java.util.ArrayList<>();
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/orders/lookup")) {
                lookupUrls.add(url);
                return List.of(order);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("data-order-needs=")))
        .andExpect(content().string(containsString(orderId.toString())))
        // The outstanding gap, and the floor the client compares the entered grade against.
        .andExpect(content().string(containsString("&quot;outstandingAmount&quot;:250.0")))
        .andExpect(content().string(containsString("&quot;qualityFloor&quot;:650")));

    assertThat(lookupUrls).isNotEmpty().allMatch(url -> url.contains("withNeeds=true"));
  }

  /**
   * The live-sync re-read behind the same figures (REQ-FE-010): {@code /inventory/order-needs}
   * answers the identical shape the page embedded, keyed by order id, so the page script decodes
   * one format for both the first paint and every refresh.
   *
   * <p>It is an AJAX-only route ({@code X-Requested-With}); a page script cannot reach {@code
   * /api/v1} on this origin, which is why the relay exists at all.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void orderNeedsAjax_ShouldAnswerTheNeedsKeyedByOrderId() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID needlessOrderId = UUID.randomUUID();

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, null, 400.0, 150.0, 250.0)));
    // An order that requires no material carries no entry at all rather than an empty one.
    JobOrderReferenceDto needless =
        new JobOrderReferenceDto(
            needlessOrderId, 72, "h2", "OPEN", null, List.of(), List.of(), List.of(), List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return url.contains("/orders/lookup")
                  ? List.of(order, needless)
                  : Collections.emptyList();
            });

    mockMvc
        .perform(get("/inventory/order-needs").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$['" + orderId + "'][0].outstandingAmount").value(250.0))
        .andExpect(jsonPath("$['" + orderId + "'][0].materialId").value(materialId.toString()))
        .andExpect(jsonPath("$['" + needlessOrderId + "']").doesNotExist());
  }

  /**
   * The second surface (REQ-INV-039): the per-entry {@code + Zuordnen} popover labels each order
   * option with what that order still needs of <em>this</em> entry's material, so choosing a target
   * does not mean opening the order first.
   *
   * <p>The label is server-rendered here — unlike the check-in form, this picker is a combobox,
   * which snapshots an option's text at enhancement time and would never see a later rewrite.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllStackEntries_ShouldLabelOrderOptionsWithTheOutstandingNeed() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Tungsten", "SCU"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            10.0,
            null,
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"));

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            1042,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, null, 400.0, 150.0, 250.0)));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(order);
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
                .param("quality", "90"))
        .andExpect(status().isOk())
        // "#1042 · noch 250,000 SCU" — the display id, then what the order still needs, in the
        // material's own unit. The decimal separator is locale-dependent, so only the digits and
        // the unit are pinned.
        .andExpect(
            content()
                .string(stringContainsInOrder("value=\"" + orderId + "\"", "#1042", "250", "SCU")));
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
   * Full-render guard for the per-game-item drilldown's server-side pagination (REQ-INV-033) — the
   * item sibling of {@code viewMaterialInventory_ShouldRenderPaginationControls}. Stubs a
   * three-page backend response and asserts the pager (next-page link at the snapped size) and the
   * size-picker options render inside the item results fragment, so a large item's stock is fully
   * reachable page by page rather than silently capped at a single fetch (ADR-0104).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewGameItemInventory_ShouldRenderPaginationControls() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(UUID.randomUUID(), "user", "User", "Item Owner", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(UUID.randomUUID(), "Port Tressler"),
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
        new PageResponse<>(List.of(item), 1, 50, 130, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/game-item/" + gameItemId).param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-game-item"))
        .andExpect(content().string(containsString("Port Tressler")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(containsString("size=50")))
        .andExpect(content().string(containsString(">200<")));
  }

  /**
   * Item stack-entries fragment guard (REQ-INV-030/031): the lazy {@code
   * /inventory/my/game-item-stack/entries} drill-down renders the game-item leaf row — whole-unit
   * amount, PIECE-typed action buttons, no mission split — carries the "Für Börse freigeben" toggle
   * (a stock-backed item offer, REQ-MARKET-002/014; unchecked here since the released-item-ids
   * lookup returned empty), and its "+ Zuordnen" picker offers only ITEM orders whose lines request
   * the entry's gameItem (requiredGameItemIds), never unrelated orders.
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
            List.of(gameItemId),
            List.of());
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId,
            99,
            "h2",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(UUID.randomUUID()),
            List.of());

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
        // No mission dimension on item rows (REQ-INV-031); the "Für Börse freigeben" toggle renders
        // as a stock-backed item offer (REQ-MARKET-002/014). data-kind="ITEM" makes the shared
        // release modal hide the quality fact; released-item-ids returned empty so it is unchecked.
        .andExpect(content().string(not(containsString("data-assoc-field=\"MISSION\""))))
        .andExpect(content().string(containsString("inv-boerse-toggle")))
        .andExpect(content().string(containsString("data-kind=\"ITEM\"")))
        .andExpect(content().string(containsString("data-boerse-status-for=\"" + itemId + "\"")))
        .andExpect(content().string(not(containsString("checked=\"checked\""))))
        // PIECE-typed action buttons keyed on the gameItem, no materialId.
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(not(containsString("data-material-id"))))
        // Order picker gate: only the order requesting this gameItem is offered.
        .andExpect(content().string(containsString("value=\"" + matchingOrderId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + unrelatedOrderId + "\""))));
  }

  /**
   * Item-leaf "Für Börse freigeben" toggle checked state (REQ-MARKET-002/014): when the batch
   * released-item-ids lookup reports the row as released, the item leaf's toggle renders checked
   * with the "Auf Börse" chip ({@code chip--primary}) — the item sibling of the material leaf
   * toggle for a stock-backed item offer.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyGameItemStackEntries_rendersBoerseToggleCheckedWhenReleased() throws Exception {
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
              if (url.contains("/inventory/my-inventory/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              // The batch "Auf Börse" lookup reports this row as released.
              if (url.contains("/material-exchange/released-item-ids")) {
                return List.of(itemId);
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
        .andExpect(content().string(containsString("inv-boerse-toggle")))
        // Released → the toggle is checked and the status chip is the primary "Auf Börse" variant.
        .andExpect(content().string(containsString("checked=\"checked\"")))
        .andExpect(content().string(containsString("chip--primary")))
        .andExpect(content().string(containsString("data-boerse-status-for=\"" + itemId + "\"")));
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
