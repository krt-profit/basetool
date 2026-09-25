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
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryGoodForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
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
 * Full-render test of the refinery create page with a flashed import draft: pre-filled goods rows
 * (including duplicate materials), inline row flags with suggestion chips and the summary banner.
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
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS), anyTypeRef()))
        .thenReturn(materials);
  }

  @Test
  void createPage_rendersImportDraftWithFlagsAndSuggestions() throws Exception {
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

    mockMvc
        .perform(
            get("/refinery-orders/create")
                .with(oidcLogin())
                .flashAttr("refineryOrderForm", form)
                .flashAttr("importIssues", List.of(unresolvedLocation))
                .flashAttr("importRowIssues", Map.of("2", List.of(unmatched)))
                .flashAttr("importGoodsMatched", 2)
                .flashAttr("importGoodsTotal", 4)
                .flashAttr("importRowsSkipped", 1))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-banner\"")))
        .andExpect(content().string(containsString("inputMaterialId_0")))
        .andExpect(content().string(containsString("inputMaterialId_1")))
        .andExpect(content().string(containsString("inputMaterialId_2")))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-row-flags-2\"")))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-suggestion-2\"")))
        .andExpect(content().string(containsString("data-material-id=\"" + SUGGESTION_ID + "\"")))
        .andExpect(content().string(containsString("value=\"" + MATERIAL_ID + "\"")))
        .andExpect(content().string(containsString("selected=\"selected\"")))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-button\"")))
        .andExpect(
            content().string(containsString("<form id=\"refineryImportForm\" class=\"no-track\"")))
        .andExpect(content().string(containsString("/js/refinery-orders-create.js")))
        .andExpect(content().string(containsString("</html>")));
  }

  @Test
  void createPage_rendersScExtractorReleaseLink_besideTheImportButton() throws Exception {
    mockMvc
        .perform(get("/refinery-orders/create").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        .andExpect(content().string(containsString("data-testid=\"refinery-extractor-link\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"https://github.com/krt-profit/basetool-sc-extractor/releases/latest\"")))
        .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")))
        .andExpect(
            result -> {
              String html = result.getResponse().getContentAsString();
              int form = html.indexOf("<form id=\"refineryImportForm\"");
              int link = html.indexOf("id=\"refineryExtractorLink\"");
              int formEnd = html.indexOf("</form>", form);
              assertTrue(
                  form >= 0 && link > form && link < formEnd,
                  "extractor link must render inside the import form, which owns the flex row");
              assertTrue(
                  html.indexOf("data-testid=\"refinery-import-button\"") < link,
                  "extractor link must follow the import button it accompanies");
            });
  }

  @Test
  void createPage_rendersZeroMatchesHintAndBlockingTint() throws Exception {
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

    mockMvc
        .perform(
            get("/refinery-orders/create")
                .with(oidcLogin())
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
   * Verifies that the create page still renders {@code 200} with an empty {@code seedUserNames} map
   * and populated {@code materials} when the owner-name lookup fails during the parallel catalog
   * fetch via {@link de.greluc.krt.profit.basetool.frontend.service .ParallelPageLoader}.
   */
  @Test
  void createPage_WhenOwnerSeedLookupFails_StillRendersWithEmptyMap() throws Exception {
    when(backendApiClient.get(eq("/api/v1/users/me"), eq(UserDto.class)))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/refinery-orders/create").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        .andExpect(model().attribute("seedUserNames", anEmptyMap()))
        .andExpect(model().attribute("materials", hasSize(2)));
  }

  @Test
  void createPage_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    mockMvc
        .perform(get("/refinery-orders/create").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
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
