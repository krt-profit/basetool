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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
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
 * Renders the material collection page {@code /orders/{id}/material-collection} on the list pattern
 * (REQ-UI-027): page head with the back link to the order, one group row per material with its
 * collection progress, the editable rows and the empty state.
 */
@SpringBootTest
class MaterialCollectionRenderTest {

  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

  private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders the page in German with the given entries from the backend.
   *
   * @param entries the entries the backend returns
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull List<MaterialCollectionEntryDto> entries)
      throws Exception {
    when(backendApiClient.get(contains("/material-collection"), anyTypeRef())).thenReturn(entries);
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of(new LocationReferenceDto(LOCATION_ID, "Lorville")));
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/orders/" + ORDER_ID + "/material-collection").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * A collection entry of the given material.
   *
   * @param id the entry id
   * @param material the material name
   * @param allocated the amount earmarked to the order
   * @param delivered whether the slice is marked delivered
   * @return the entry
   */
  private static @NotNull MaterialCollectionEntryDto entry(
      @NotNull UUID id, @NotNull String material, double allocated, boolean delivered) {
    return new MaterialCollectionEntryDto(
        id,
        3L,
        "Alice",
        UUID.randomUUID(),
        "Lorville",
        LOCATION_ID,
        material,
        512.0,
        allocated + 2,
        allocated,
        delivered);
  }

  /** Entries render grouped per material with the delivered share, on the list pattern. */
  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void rendersGroupedRowsWithProgress() throws Exception {
    UUID deliveredId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    String html =
        render(
            List.of(
                entry(deliveredId, "Laranite", 1.0, true),
                entry(openId, "Laranite", 3.0, false),
                entry(UUID.randomUUID(), "Agricium", 4.0, false)));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*href=\"/orders/" + ORDER_ID + "\"")
        .containsPattern("<h1>Materialsammelübersicht</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("btn--cta");
    assertThat(html.substring(html.indexOf("<main"), html.indexOf("</main>")))
        .doesNotContainPattern("class=\"[^\"]*krtm-");
    assertThat(html)
        .contains("id=\"material-collection-results\"")
        .contains("id=\"material-collection-table\"")
        .contains("class=\"data-table data-table--stack collection-table\"")
        .containsPattern("class=\"collection-group__title\">Laranite<")
        .containsPattern("class=\"collection-group__title\">Agricium<")
        .contains(">1 von 4 geliefert<")
        .contains(">0 von 4 geliefert<")
        .contains(">13 % geliefert<")
        .contains("data-progress-template=\"%0 % geliefert\"")
        .contains("data-progress-template=\"%0 von %1 geliefert\"")
        .contains("data-krtm-width=\"25\"")
        .contains("data-inventory-id=\"" + deliveredId + "\"")
        .contains("data-job-order-id=\"" + ORDER_ID + "\"")
        .containsPattern("class=\"location-select[^\"]*\"[^>]*data-inventory-id=\"" + openId + "\"")
        .contains("von 5 im Bestand")
        .doesNotContain("data-testid=\"empty-state\"");
    assertThat(html.split("collection-group__head", -1)).hasSize(3);
  }

  /** No entries render the empty state and no table. */
  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void rendersTheEmptyState() throws Exception {
    String html = render(List.of());

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Lagereinträge für diesen Auftrag vorhanden.")
        .contains("id=\"material-collection-results\"")
        .doesNotContain("id=\"material-collection-table\"")
        .doesNotContain("data-collection-progress");
  }
}
