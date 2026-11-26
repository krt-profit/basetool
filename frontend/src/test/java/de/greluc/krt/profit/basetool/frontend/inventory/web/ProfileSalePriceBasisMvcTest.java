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

import de.greluc.krt.profit.basetool.frontend.inventory.model.MySalePriceBasisRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.MySalePriceBasisResponse;
import de.greluc.krt.profit.basetool.frontend.inventory.model.SalePriceBasis;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.List;
import java.util.Locale;
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
 * Verifies the profile's "Mein Lager" price basis section (REQ-INV-072): the three choices with the
 * stored one checked, the average when the setting cannot be read, the in-place save with its
 * relayed conflict, and the no-script form post.
 */
@SpringBootTest
class ProfileSalePriceBasisMvcTest {

  private static final String BACKEND = "/api/v1/inventory/my-inventory/sale-price-basis";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void theSectionOffersTheThreeBasesWithTheStoredOneChecked() throws Exception {
    when(backendApiClient.get(BACKEND, MySalePriceBasisResponse.class))
        .thenReturn(new MySalePriceBasisResponse(SalePriceBasis.HIGHEST, 3L));

    String html = profile();

    assertThat(html)
        .contains("id=\"profile-sale-price-form\"", "href=\"#profile-section-sale-price\"")
        .contains("7 · Verkaufswert im Lager", "Durchschnittspreis", "Bestpreis", "Minimalpreis")
        .containsPattern("value=\"HIGHEST\"[^>]*checked")
        .doesNotContainPattern("value=\"AVERAGE\"[^>]*checked")
        .containsPattern("id=\"profile-sale-price-form\"(?s).*?name=\"version\"[^>]*value=\"3\"");
  }

  @Test
  void aFailedReadShowsTheAverageAtVersionZero() throws Exception {
    when(backendApiClient.get(BACKEND, MySalePriceBasisResponse.class))
        .thenThrow(new BackendServiceException("down", null, 503));

    assertThat(profile())
        .containsPattern("value=\"AVERAGE\"[^>]*checked")
        .containsPattern("id=\"profile-sale-price-form\"(?s).*?name=\"version\"[^>]*value=\"0\"");
  }

  @Test
  void anAjaxSaveRelaysTheChoiceAndAnswersWithTheNewVersion() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MySalePriceBasisResponse.class)))
        .thenReturn(new MySalePriceBasisResponse(SalePriceBasis.LOWEST, 4L));

    mockMvc
        .perform(
            post("/profile/sale-price-basis")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salePriceBasis\":\"LOWEST\",\"version\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.salePriceBasis").value("LOWEST"))
        .andExpect(jsonPath("$.version").value(4));

    verify(backendApiClient)
        .put(
            BACKEND,
            new MySalePriceBasisRequest(SalePriceBasis.LOWEST, 3L),
            MySalePriceBasisResponse.class);
  }

  @Test
  void aStaleAjaxSaveRelaysTheConflict() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MySalePriceBasisResponse.class)))
        .thenThrow(
            new BackendServiceException(
                "stale", null, 409, "OPTIMISTIC_LOCK", null, List.of(), "Changed meanwhile."));

    mockMvc
        .perform(
            post("/profile/sale-price-basis")
                .with(oidcLogin())
                .with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salePriceBasis\":\"HIGHEST\",\"version\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
  }

  @Test
  void theNoScriptFormPostSavesAndRedirects() throws Exception {
    mockMvc
        .perform(
            post("/profile/sale-price-basis")
                .with(oidcLogin())
                .with(csrf())
                .param("salePriceBasis", "HIGHEST")
                .param("version", "2"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/profile"))
        .andExpect(flash().attribute("successToast", "notification.success.save"));

    verify(backendApiClient)
        .put(
            BACKEND,
            new MySalePriceBasisRequest(SalePriceBasis.HIGHEST, 2L),
            MySalePriceBasisResponse.class);
  }

  @Test
  void aStaleNoScriptFormPostShowsTheConflictToast() throws Exception {
    when(backendApiClient.put(eq(BACKEND), any(), eq(MySalePriceBasisResponse.class)))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            post("/profile/sale-price-basis")
                .with(oidcLogin())
                .with(csrf())
                .param("salePriceBasis", "LOWEST")
                .param("version", "1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorToast", "error.concurrency.conflict"));
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
}
