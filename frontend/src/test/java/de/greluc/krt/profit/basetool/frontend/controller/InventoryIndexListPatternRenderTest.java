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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the aggregated stock overview {@code /inventory} on the list pattern (REQ-UI-027): page
 * head with eyebrow, count and one primary action, the view tabs, the row-link table with numeric
 * columns, and the empty state instead of a {@code colspan} row.
 */
@SpringBootTest
class InventoryIndexListPatternRenderTest {

  private static final UUID MATERIAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static final UUID GAME_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /inventory} in German with the given page from the backend.
   *
   * @param page the page the backend returns
   * @param query the query string, without {@code ?}
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(
      @NotNull PageResponse<AggregatedInventoryDto> page, @NotNull String query) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/inventory?" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A one-row page.
   *
   * @param row the only row
   * @return the page
   */
  private static @NotNull PageResponse<AggregatedInventoryDto> pageOf(
      @NotNull AggregatedInventoryDto row) {
    return new PageResponse<>(List.of(row), 0, 20, 1L, 1, List.of());
  }

  /**
   * An empty page.
   *
   * @return the page
   */
  private static @NotNull PageResponse<AggregatedInventoryDto> emptyPage() {
    return new PageResponse<>(List.<AggregatedInventoryDto>of(), 0, 20, 0L, 0, List.of());
  }

  /**
   * A material row in SCU.
   *
   * @return the row
   */
  private static @NotNull AggregatedInventoryDto materialRow() {
    MaterialDto material =
        new MaterialDto(
            MATERIAL_ID,
            "Laranite",
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
    return new AggregatedInventoryDto(material, null, 512.0, 700.0, 12.5);
  }

  /** The material view renders on the pattern: head, tabs, row link, numeric cells, foot. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void materialViewRendersTheListPattern() throws Exception {
    String html = render(pageOf(materialRow()), "");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Flotte &amp; Logistik<")
        .containsPattern("<h1>Lagerverwaltung</h1>")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"inventory-results\"")
        .contains("data-testid=\"inventory-my-link\"")
        .contains("data-testid=\"inventory-global-link\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("data-trigger=\"navigate-href\"");
    assertThat(html.substring(html.indexOf("<main"), html.indexOf("</main>")))
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"lager-view-material\"")
        .contains("data-testid=\"lager-view-items\"")
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern(
            "class=\"row-link\"[^>]*href=\"/inventory/all\\?materialIds=" + MATERIAL_ID)
        .contains(">Laranite<")
        .contains("data-label=\"Ø Qualität\"")
        .contains(">12,500 SCU<")
        .contains("data-list-total=\"1\"")
        .doesNotContain("data-testid=\"empty-state\"");
  }

  /** An empty material view renders the empty state with its action and no table. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyMaterialViewRendersTheEmptyState() throws Exception {
    String html = render(emptyPage(), "");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Einträge vorhanden.")
        .contains("data-testid=\"empty-state-action\"")
        .doesNotContain("colspan=")
        .doesNotContain("data-table--stack")
        .doesNotContain("data-list-total");
  }

  /** The items view links each row to the item tree and has no quality columns. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemsViewRendersTheListPattern() throws Exception {
    AggregatedInventoryDto row =
        new AggregatedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(GAME_ITEM_ID, "Quantum Drive XL-1", "RSI", null),
            null,
            null,
            7.0);

    String html = render(pageOf(row), "view=items");

    assertThat(html)
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern(
            "class=\"row-link\"[^>]*href=\"/inventory/all\\?view=items&amp;gameItemIds="
                + GAME_ITEM_ID)
        .containsPattern("class=\"cell-sub\">RSI<")
        .doesNotContain("Max. Qualit");
  }

  /** An empty items view renders the items empty state. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyItemsViewRendersTheEmptyState() throws Exception {
    String html = render(emptyPage(), "view=items");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Item-Bestände vorhanden.")
        .doesNotContain("data-table--stack");
  }
}
