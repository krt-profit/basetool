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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.frontend.model.dto.ImportSuggestionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full-template-render test of the refinery create page with a flashed import draft (#435): the
 * pre-filled goods rows (incl. duplicate materials), the inline row flags with suggestion chips,
 * and the summary banner must all survive Thymeleaf rendering — pure controller tests miss
 * render-time 500s, which has bitten this project before.
 */
@SpringBootTest
class RefineryOrderCreateImportRenderTest {

  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID SUGGESTION_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    MaterialDto raw =
        new MaterialDto(
            MATERIAL_ID,
            "Stileron (Raw)",
            "RAW",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    MaterialDto suggested =
        new MaterialDto(
            SUGGESTION_ID,
            "Aluminum (Raw)",
            "RAW",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    PageResponse<MaterialDto> materials =
        new PageResponse<>(List.of(raw, suggested), 0, 1000, 2, 1, Collections.emptyList());
    when(backendApiClient.getCached(eq("/api/v1/materials?size=1000"), anyTypeRef(), eq(true)))
        .thenReturn(materials);
  }

  @Test
  void createPage_rendersImportDraftWithFlagsAndSuggestions() throws Exception {
    // Given — a flashed pre-fill: two duplicate-material rows + one unmatched row with suggestions
    RefineryOrderForm form = new RefineryOrderForm();
    List<RefineryGoodForm> goods = new ArrayList<>();
    goods.add(good(MATERIAL_ID, 957, 448, 618));
    goods.add(good(MATERIAL_ID, 300, 140, 385));
    goods.add(good(null, 500, 230, 0));
    form.setGoods(goods);

    ImportIssueDto unmatched =
        new ImportIssueDto(
            "goods[2].inputMaterial",
            "ALUMINIUM (ORE)",
            ImportIssueCode.UNMATCHED_MATERIAL,
            ImportIssueSeverity.WARNING,
            0.95,
            List.of(new ImportSuggestionDto(SUGGESTION_ID, "Aluminum (Raw)", 0.889)));
    ImportIssueDto unresolvedLocation =
        new ImportIssueDto(
            "location",
            null,
            ImportIssueCode.UNRESOLVED_LOCATION,
            ImportIssueSeverity.WARNING,
            null,
            null);

    // When / Then — the full template renders with banner, inline flag and suggestion chip
    mockMvc
        .perform(
            get("/refinery-orders/create")
                .with(oidcLogin())
                .flashAttr("refineryOrderForm", form)
                .flashAttr("importIssues", List.of(unresolvedLocation))
                // String key on purpose: the JSON Redis session stringifies flash-map keys, so
                // the controller flashes (and the template matches) string-form indices.
                .flashAttr("importRowIssues", Map.of("2", List.of(unmatched)))
                .flashAttr("importGoodsMatched", 2)
                .flashAttr("importGoodsTotal", 4)
                .flashAttr("importRowsSkipped", 1))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        // banner with counters and the order-level finding
        .andExpect(content().string(containsString("data-testid=\"refinery-import-banner\"")))
        // duplicate-material rows render as separate selects
        .andExpect(content().string(containsString("inputMaterialId_0")))
        .andExpect(content().string(containsString("inputMaterialId_1")))
        .andExpect(content().string(containsString("inputMaterialId_2")))
        // the unmatched row carries the inline flag block and the one-click suggestion chip
        .andExpect(content().string(containsString("data-testid=\"refinery-import-row-flags-2\"")))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-suggestion-2\"")))
        .andExpect(content().string(containsString("data-material-id=\"" + SUGGESTION_ID + "\"")))
        // the matched rows keep their pre-selected material: some option must carry the
        // selected marker (a bare value="<id>" match would hit every dropdown's option list)
        .andExpect(content().string(containsString("value=\"" + MATERIAL_ID + "\"")))
        .andExpect(content().string(containsString("selected=\"selected\"")))
        // the import upload control is present and exempt from the unsaved-changes guard:
        // picking a file must not arm the leave-page warning (the import replaces the form
        // wholesale, REQ-REFINERY-013)
        .andExpect(content().string(containsString("data-testid=\"refinery-import-button\"")))
        .andExpect(
            content().string(containsString("<form id=\"refineryImportForm\" class=\"no-track\"")))
        .andExpect(content().string(containsString("importForm.requestSubmit()")))
        .andExpect(content().string(containsString("</html>")));
  }

  @Test
  void createPage_rendersZeroMatchesHintAndBlockingTint() throws Exception {
    // Given — a draft where no row matched and the order is un-quoted (BLOCKING finding)
    RefineryOrderForm form = new RefineryOrderForm();
    form.setGoods(new ArrayList<>(List.of(good(null, 250, null, 618))));
    ImportIssueDto unquotedOrder =
        new ImportIssueDto(
            "quoted",
            null,
            ImportIssueCode.UNQUOTED_ORDER,
            ImportIssueSeverity.BLOCKING,
            null,
            null);

    // When / Then — the banner adds the explicit zero-matches hint and the BLOCKING finding
    // renders with the danger tint (REQ-REFINERY-016 / REQ-REFINERY-014)
    mockMvc
        .perform(
            get("/refinery-orders/create")
                .with(oidcLogin())
                // pin the resolved locale so the asserted bundle text is deterministic
                .locale(java.util.Locale.GERMAN)
                .flashAttr("refineryOrderForm", form)
                .flashAttr("importIssues", List.of(unquotedOrder))
                .flashAttr("importRowIssues", Map.of())
                .flashAttr("importGoodsMatched", 0)
                .flashAttr("importGoodsTotal", 3)
                .flashAttr("importRowsSkipped", 3))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"refinery-import-banner\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "Keine Zeile konnte automatisch zugeordnet werden - bitte Materialien"
                            + " manuell wählen.")))
        .andExpect(content().string(containsString("import-flag-danger")));
  }

  @Test
  void createPage_rendersBackendProblemDetailVerbatim() throws Exception {
    // Given — an envelope-level reject surfaced as verbatim localized text
    mockMvc
        .perform(
            get("/refinery-orders/create")
                .with(oidcLogin())
                .flashAttr("importErrorText", "Schema-Version wird nicht unterstützt."))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"refinery-import-error\"")))
        .andExpect(content().string(containsString("Schema-Version wird nicht unterstützt.")));
  }

  /**
   * Graceful-degradation guard for the parallelized create-form catalog fan-out (#769): the lookups
   * run concurrently through the real {@link de.greluc.krt.profit.basetool.frontend.service
   * .ParallelPageLoader}, but each fetch helper swallows its own failure and returns an empty
   * list/map, so {@code allOf(...).join()} must never propagate an exception. Here the users lookup
   * throws while the materials lookup (stubbed in {@code setUp}) succeeds; the page must still
   * render {@code 200} with an empty {@code users} model attribute and the populated {@code
   * materials} attribute — exactly as the serial version degraded.
   */
  @Test
  void createPage_WhenOneCatalogFetchFails_StillRendersWithEmptyList() throws Exception {
    when(backendApiClient.get(eq("/api/v1/users?size=1000"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/refinery-orders/create").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        .andExpect(model().attribute("users", empty()))
        .andExpect(model().attribute("materials", hasSize(2)));
  }

  private static RefineryGoodForm good(
      UUID materialId, Integer inputQty, Integer outputQty, Integer quality) {
    RefineryGoodForm row = new RefineryGoodForm();
    row.setInputMaterialId(materialId);
    row.setInputQuantity(inputQty);
    row.setOutputQuantity(outputQty);
    row.setQuality(quality);
    return row;
  }
}
