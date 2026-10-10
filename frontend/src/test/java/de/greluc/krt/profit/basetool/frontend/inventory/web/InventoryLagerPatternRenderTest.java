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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.time.Instant;
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
 * Renders the four Lager pages on the 2026-10 page patterns (REQ-UI-027): the page head with the
 * back link and at most one primary action, the toolbar with the filter popover and its chips, the
 * tree table inside a flush card, the hidden selection bar of "Mein Lager", the empty states, and
 * the drilldowns as data tables.
 */
@SpringBootTest
class InventoryLagerPatternRenderTest {

  private static final UUID MATERIAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

  private static final UUID GAME_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

  private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders a page in German, answering the backend call whose URL contains {@code urlPart} with
   * {@code body} and every other read with an empty list.
   *
   * @param path the page path including its query
   * @param urlPart the backend URL fragment to answer with {@code body}
   * @param body the answer for that read
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull String path, @NotNull String urlPart, Object body)
      throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return url.contains(urlPart) ? body : Collections.emptyList();
            });
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return url.contains(urlPart) ? body : Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(path).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The {@code <main>} element of a rendered page.
   *
   * @param html the rendered page
   * @return the markup between {@code <main} and {@code </main>}
   */
  private static @NotNull String main(@NotNull String html) {
    return html.substring(html.indexOf("<main"), html.indexOf("</main>"));
  }

  /**
   * One material group holding one stack at one place.
   *
   * @return the grouped list
   */
  private static @NotNull List<GroupedInventoryDto> materialGroups() {
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(USER_ID, "owner", "Owner", "Lager Owner", null),
            new LocationReferenceDto(LOCATION_ID, "ARC-L1"),
            700,
            false,
            false,
            null,
            12.5,
            700.0,
            700,
            1);
    return List.of(
        new GroupedInventoryDto(
            new MaterialReferenceDto(MATERIAL_ID, "Quantanium", "SCU"),
            null,
            12.5,
            700.0,
            700,
            List.of(stack)));
  }

  /**
   * One drilldown row of the given material or game item.
   *
   * @param material the row's material, or {@code null} for an item row
   * @param gameItem the row's game item, or {@code null} for a material row
   * @return the one-row page
   */
  private static @NotNull PageResponse<InventoryItemDto> drillPage(
      MaterialReferenceDto material, InventoryGameItemReferenceDto gameItem) {
    InventoryItemDto row =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(USER_ID, "owner", "Owner", "Lager Owner", null),
            material,
            gameItem,
            new LocationReferenceDto(LOCATION_ID, "ARC-L1"),
            material != null ? 700 : null,
            4.0,
            false,
            false,
            List.of(),
            4.0,
            List.of(),
            4.0,
            null,
            null,
            1L,
            true,
            Instant.parse("2026-10-01T00:00:00Z"));
    return new PageResponse<>(List.of(row), 0, 50, 1L, 1, List.of());
  }

  /** "Mein Lager" renders head, toolbar, popover, chips, tree card and the hidden selection bar. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myLagerRendersTheListPatternWithTheSelectionBar() throws Exception {
    String html = render("/inventory/my", "/inventory/my-inventory/grouped", materialGroups());
    String main = main(html);

    assertThat(main)
        .contains("class=\"page-head\"")
        .containsPattern(
            "class=\"page-eyebrow\" href=\"/inventory\"[\\s\\S]*?>Lagerverwaltung</span>")
        .contains("<h1>Mein Lager</h1>")
        .containsPattern(
            "href=\"/inventory/input\\?source=my\"\\s+class=\"btn btn--cta\""
                + "\\s+data-testid=\"page-head-primary\"\\s*>Einbuchen<")
        .contains("class=\"toolbar lager-toolbar\"")
        .contains("data-testid=\"segment-personalScope-all\"")
        .contains("data-testid=\"segment-personalScope-personal\"")
        .contains("data-testid=\"segment-personalScope-shared\"")
        .contains("data-testid=\"lager-filter-toggle\"")
        .contains("data-filter-chips data-filter-form=\"myFilterForm\"")
        .contains("data-chip-family=\"matCheck\"")
        .contains("class=\"card card--flush lager-tree-card\"")
        .contains("class=\"tree-table lager-material-tree\"")
        .contains(">Quantanium<")
        .containsPattern("id=\"bulkCheckoutBar\"[^>]*hidden")
        .contains("id=\"lager-bulk-more\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("data-testid=\"empty-state\"")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main.indexOf("id=\"bulkCheckoutBar\""))
        .isGreaterThan(main.indexOf("id=\"myInventoryTableContainer\""));
  }

  /**
   * An empty "Mein Lager" keeps the tree's storage attributes and shows the empty state instead of
   * a column head.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyMyLagerRendersTheEmptyState() throws Exception {
    String main = main(render("/inventory/my", "/never", List.of()));

    assertThat(main)
        .contains("id=\"inventoryTable\"")
        .contains("tree-table--empty")
        .contains("data-testid=\"empty-state\"")
        .contains("Du hast noch nichts eingelagert.")
        .doesNotContain("tree-row tree-head")
        .doesNotContain("colspan");
  }

  /** The items view of "Mein Lager" carries its own form, chips source and empty state. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myLagerItemsViewRendersItsOwnFilterForm() throws Exception {
    String main = main(render("/inventory/my?view=items", "/never", List.of()));

    assertThat(main)
        .containsPattern("class=\"page-eyebrow\" href=\"/inventory\\?view=items\"")
        .contains("id=\"myItemsFilterForm\"")
        .contains("data-filter-form=\"myItemsFilterForm\"")
        .contains("data-chip-family=\"gameItemCheck\"")
        .contains("Du hast noch keine Items eingelagert.")
        .doesNotContain("id=\"minQuality\"");
  }

  /**
   * The org-wide Lager offers "Einbuchen" to a Logistician as its one primary action, and has no
   * selection bar.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void globalLagerRendersTheListPatternForALogistician() throws Exception {
    String main = main(render("/inventory/all", "/inventory/all/grouped", materialGroups()));

    assertThat(main)
        .contains("<h1>Globales Lager</h1>")
        .containsPattern(
            "href=\"/inventory/input\\?source=admin\"\\s+class=\"btn btn--cta\""
                + "\\s+data-testid=\"page-head-primary\"\\s*>Einbuchen<")
        .contains("data-testid=\"lager-filter-toggle\"")
        .contains("data-filter-chips data-filter-form=\"globalFilterForm\"")
        .contains("class=\"tree-table lager-material-tree\"")
        .contains(">Lager Owner<")
        .doesNotContain("id=\"bulkCheckoutBar\"")
        .doesNotContain("delete-all-global-inventory-btn")
        .doesNotContain("hud-box")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(main.split("btn--cta", -1)).hasSize(2);
  }

  /** A member sees the org-wide Lager without any page action. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void globalLagerHasNoPrimaryActionForAMember() throws Exception {
    String main = main(render("/inventory/all", "/never", List.of()));

    assertThat(main)
        .doesNotContain("btn--cta")
        .doesNotContain("overflow-menu")
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Einträge gefunden.");
  }

  /** Emptying the Lager is an admin's rare, destructive action and sits in the overflow menu. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void globalLagerMovesEmptyingIntoTheOverflowMenuForAnAdmin() throws Exception {
    String html = render("/inventory/all", "/never", List.of());

    assertThat(main(html))
        .containsPattern(
            "class=\"overflow-menu__panel\"[\\s\\S]*id=\"delete-all-global-inventory-btn\""
                + " class=\"overflow-menu__item overflow-menu__item--danger\"");
    assertThat(html).contains("id=\"delete-all-global-inventory-modal\"");
  }

  /** The material drilldown names the material in the head and lists its rows as a data table. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void materialDrilldownRendersADataTable() throws Exception {
    String main =
        main(
            render(
                "/inventory/material/" + MATERIAL_ID,
                "/api/v1/inventory/material/",
                drillPage(new MaterialReferenceDto(MATERIAL_ID, "Quantanium", "SCU"), null)));

    assertThat(main)
        .containsPattern("class=\"page-eyebrow\" href=\"/inventory\"")
        .contains("<h1>Quantanium</h1>")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("id=\"materialSelect\"")
        .contains("id=\"inventory-material-results\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("class=\"cell-title\">Lager Owner<")
        .contains(">ARC-L1<")
        .contains("data-list-total=\"1\"")
        .doesNotContain("btn--cta")
        .doesNotContain("hud-box")
        .doesNotContain("colspan")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
  }

  /** An empty material drilldown shows the empty state instead of a table. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyMaterialDrilldownRendersTheEmptyState() throws Exception {
    String main =
        main(
            render(
                "/inventory/material/" + MATERIAL_ID,
                "/api/v1/inventory/material/",
                new PageResponse<>(List.<InventoryItemDto>of(), 0, 50, 0L, 0, List.of())));

    assertThat(main)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("data-table--stack")
        .doesNotContain("colspan");
  }

  /** The game-item drilldown names the item in the head and links back to the items view. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void gameItemDrilldownRendersADataTable() throws Exception {
    String main =
        main(
            render(
                "/inventory/game-item/" + GAME_ITEM_ID,
                "/api/v1/inventory/game-item/",
                drillPage(
                    null,
                    new InventoryGameItemReferenceDto(
                        GAME_ITEM_ID, "Quantum Drive XL-1", "RSI", "VEHICLE_ITEM"))));

    assertThat(main)
        .containsPattern("class=\"page-eyebrow\" href=\"/inventory\\?view=items\"")
        .contains("<h1>Quantum Drive XL-1</h1>")
        .contains("class=\"data-table data-table--stack\"")
        .contains("class=\"cell-title\">Lager Owner<")
        .doesNotContain("btn--cta")
        .doesNotContain("hud-box")
        .doesNotContainPattern("class=\"[^\"]*krtm-");
  }
}
