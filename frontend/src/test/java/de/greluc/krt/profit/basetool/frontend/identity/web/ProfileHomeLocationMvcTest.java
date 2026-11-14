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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyHomeLocationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyHomeLocationResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the profile's section "my home location" (REQ-PATCH-030): the curated options with the
 * stored choice, the in-place save with its relayed conflict, and the no-script form post.
 */
@SpringBootTest
class ProfileHomeLocationMvcTest {

  private static final String BACKEND = "/api/v1/users/me/home-location";
  private static final UUID LORVILLE = UUID.fromString("5a1e0000-0000-4000-8000-00000000a001");
  private static final UUID AREA18 = UUID.fromString("5a1e0000-0000-4000-8000-00000000a002");

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void theSectionOffersTheCuratedLocationsByNameWithTheStoredOneSelected() throws Exception {
    offer(home(LORVILLE, "Lorville"), home(AREA18, "Area18"));
    when(backendApiClient.get(BACKEND, MyHomeLocationResponse.class))
        .thenReturn(new MyHomeLocationResponse(LORVILLE, 3L));

    String html = profile();

    assertThat(html)
        .contains("id=\"profile-home-location-form\"")
        .contains("href=\"#profile-section-home-location\"")
        .containsPattern("value=\"" + LORVILLE + "\"[^>]*selected")
        .doesNotContainPattern("value=\"\"\\s+disabled[^>]*selected");
    assertThat(html.indexOf(">Area18<")).isLessThan(html.indexOf(">Lorville<"));
    assertThat(html).containsPattern("name=\"version\"[^>]*value=\"3\"");
  }

  @Test
  void aMemberWithoutAChoiceSeesThePlaceholderSelected() throws Exception {
    offer(home(LORVILLE, "Lorville"));
    when(backendApiClient.get(BACKEND, MyHomeLocationResponse.class))
        .thenReturn(new MyHomeLocationResponse(null, 0L));

    assertThat(profile())
        .containsPattern("value=\"\"\\s+disabled[^>]*selected")
        .doesNotContainPattern("value=\"" + LORVILLE + "\"[^>]*selected");
  }

  @Test
  void withoutCuratedLocationsTheSectionSaysSo() throws Exception {
    offer();

    assertThat(profile()).contains("data-testid=\"profile-home-location-empty\"");
  }

  @Test
  void anAjaxSaveRelaysTheChoiceAndAnswersWithTheNewVersion() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MyHomeLocationResponse.class)))
        .thenReturn(new MyHomeLocationResponse(AREA18, 4L));

    mockMvc
        .perform(
            post("/profile/home-location")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"homeLocationId\":\"" + AREA18 + "\",\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homeLocationId").value(AREA18.toString()))
        .andExpect(jsonPath("$.version").value(4));

    verify(backendApiClient)
        .put(BACKEND, new MyHomeLocationRequest(AREA18, 3L), MyHomeLocationResponse.class);
  }

  @Test
  void aStaleAjaxSaveRelaysTheConflict() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MyHomeLocationResponse.class)))
        .thenThrow(
            new BackendServiceException(
                "stale", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Changed meanwhile."));

    mockMvc
        .perform(
            post("/profile/home-location")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"homeLocationId\":\"" + AREA18 + "\",\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  void theNoScriptFormPostSavesAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/profile/home-location")
                .with(oidcLogin())
                .with(csrf())
                .param("homeLocationId", LORVILLE.toString())
                .param("version", "2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/profile"))
        .andExpect(flash().attribute("successToast", "notification.success.save"));

    verify(backendApiClient)
        .put(BACKEND, new MyHomeLocationRequest(LORVILLE, 2L), MyHomeLocationResponse.class);
  }

  @Test
  void theNoScriptFormPostWithoutAChoiceAsksForOne() throws Exception {
    mockMvc
        .perform(
            post("/profile/home-location").with(oidcLogin()).with(csrf()).param("version", "2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorToast", "profile.homeLocation.required"));
  }

  /**
   * Renders the profile page in German.
   *
   * @return the HTML
   * @throws Exception if the request fails
   */
  private String profile() throws Exception {
    return mockMvc
        .perform(get("/profile").locale(Locale.GERMAN).with(oidcLogin()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Answers the curated home locations.
   *
   * @param locations the locations, in the backend's order
   */
  private void offer(LocationDto... locations) {
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_HOME), anyTypeRef()))
        .thenReturn(List.of(locations));
  }

  /**
   * Builds a curated home location.
   *
   * @param id its id
   * @param name its name
   * @return the location
   */
  private static LocationDto home(UUID id, String name) {
    return new LocationDto(id, name, null, false, true, 0L);
  }
}
