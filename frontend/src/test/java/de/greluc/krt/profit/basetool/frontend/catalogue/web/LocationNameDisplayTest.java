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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
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
 * The admin locations page shows the P4K name of a location's linked starmap object in the page
 * language beside the curated name, which stays the main value (REQ-P4K-023, REQ-P4K-037).
 */
@SpringBootTest
class LocationNameDisplayTest {

  private static final String CATALOGUE_WALK =
      "/api/v1/locations?size=1000&sort=name,asc&includeHidden=true&page={page}";

  private static final String DISPLAYED_PAGE =
      "/api/v1/locations?includeHidden=true&sort=name,asc&page={page}&size={size}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    LocationDto hub =
        new LocationDto(
            UUID.randomUUID(),
            "KRT-Hauptlager",
            "Lager in der Stadt",
            false,
            false,
            2L,
            UUID.randomUUID(),
            "New Babbage",
            "Neu-Babbage");
    LocationDto curatedOnly =
        new LocationDto(UUID.randomUUID(), "Freies Lager", null, false, false, 1L);
    PageResponse<LocationDto> rows =
        new PageResponse<>(List.of(hub, curatedOnly), 0, 25, 2, 1, List.of());
    when(backendApiClient.get(eq(CATALOGUE_WALK), anyTypeRef(), eq(0))).thenReturn(rows);
    when(backendApiClient.get(eq(DISPLAYED_PAGE), anyTypeRef(), eq(0), eq(25))).thenReturn(rows);
  }

  /**
   * Renders the locations page in the given language.
   *
   * @param locale the request locale
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull Locale locale) throws Exception {
    return mockMvc
        .perform(get("/admin/locations").param("lang", locale.getLanguage()).locale(locale))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void aGermanPage_showsTheGermanP4kName_besideTheCuratedName() throws Exception {
    String html = render(Locale.GERMAN);

    assertThat(html)
        .containsPattern("class=\"cell-title\">KRT-Hauptlager<")
        .containsPattern("data-testid=\"location-p4k-name\"[^>]*>Neu-Babbage<")
        .doesNotContain(">New Babbage<")
        .containsPattern("class=\"cell-title\">Freies Lager<");
    assertThat(html.split("data-testid=\"location-p4k-name\"", -1)).hasSize(2);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void anEnglishPage_showsTheEnglishP4kName_besideTheCuratedName() throws Exception {
    String html = render(Locale.ENGLISH);

    assertThat(html)
        .containsPattern("class=\"cell-title\">KRT-Hauptlager<")
        .containsPattern("data-testid=\"location-p4k-name\"[^>]*>New Babbage<")
        .doesNotContain(">Neu-Babbage<");
  }
}
