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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the Profit Basetool app mark and its favicon set on every rendered page (REQ-UI-019),
 * asserting on the rendered HTML that the DAS KARTELL org mark does not appear.
 */
@SpringBootTest
class BrandMarkRenderMvcTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef()))
        .thenReturn(null);
  }

  /**
   * {@code "/"} is permitAll, so the anonymous home page is the earliest surface a visitor sees and
   * the one whose branding a broken include chain would silently change.
   */
  @Test
  void homePage_ShouldWearTheBasetoolMarkInTheHeader_ForAnonymousVisitor() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("logos/basetool-logo.svg")));
  }

  /**
   * The favicon set ships as one SVG plus two exact-size PNG rasters plus the opaque touch icon.
   * Asserting all four in one test keeps them coupled: dropping the PNG fallbacks would leave older
   * engines with no tab icon at all, and dropping {@code apple-touch-icon} would let iOS fall back
   * to a screenshot of the page.
   */
  @Test
  void homePage_ShouldLinkTheFullBasetoolFaviconSet() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("logos/basetool-favicon.svg")))
        .andExpect(content().string(containsString("logos/basetool-favicon-32.png")))
        .andExpect(content().string(containsString("logos/basetool-favicon-16.png")))
        .andExpect(content().string(containsString("logos/basetool-appicon-512.png")));
  }

  /** The org mark and the org favicon do not appear on an app page. */
  @Test
  void homePage_ShouldNotFallBackToTheOrgMarkOrOrgFavicon() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("logos/krt.webp"))))
        .andExpect(content().string(not(containsString("logos/krt-favicon.webp"))));
  }

  /**
   * Each logo asset the templates reference exists on the classpath.
   *
   * @param asset file name inside {@code META-INF/resources/logos/}, as referenced from {@code
   *     fragments/head.html} and the page headers
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "basetool-logo.svg",
        "basetool-logo-white.svg",
        "basetool-favicon.svg",
        "basetool-favicon-16.png",
        "basetool-favicon-32.png",
        "basetool-favicon-64.png",
        "basetool-appicon-512.png"
      })
  void brandAsset_ShouldShipOnTheClasspath(String asset) {
    assertThat(new ClassPathResource("META-INF/resources/logos/" + asset).exists())
        .as("brand asset %s must ship under META-INF/resources/logos/", asset)
        .isTrue();
  }

  /**
   * The {@code /logos} directory ships only the Basetool logo family.
   *
   * @throws IOException if the classpath cannot be scanned
   */
  @Test
  void logosDirectory_ShipsOnlyTheBasetoolLogoFamily() throws IOException {
    Resource[] shipped =
        new PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/resources/logos/*.*");

    assertThat(shipped).isNotEmpty();
    assertThat(shipped)
        .extracting(Resource::getFilename)
        .allSatisfy(name -> assertThat(name).startsWith("basetool-"));
  }
}
