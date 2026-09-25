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
 * Pins the Profit Basetool app mark and its favicon set to every rendered page (REQ-UI-019).
 *
 * <p>The app used to wear the DAS KARTELL org mark ({@code logos/krt.webp}) in the header and the
 * padded org favicon ({@code logos/krt-favicon.webp}) in the browser tab. Both were replaced by the
 * dedicated Basetool logo family, so two regressions are now possible and neither would fail any
 * other test: a template could drift back to the org mark, and a favicon {@code <link>} could be
 * dropped while the page keeps rendering perfectly. Since FE-PERF-04 (2026-09-22) the two org files
 * no longer ship from the frontend at all — the org-branded PDF exports read the backend's own
 * {@code krt.png} — so a template that drifted back would now 404 in the browser, which no
 * server-side render sees either; the negative match below is still the guard.
 *
 * <p>The {@code krt.*} assertions are therefore written as *negative* matches on the rendered HTML
 * rather than as a grep over the template sources: only the rendered output proves that no fragment
 * in the include chain put the org mark back.
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

  /**
   * The org mark and the org favicon must not reappear on an app page. The generated PDF exports
   * are org documents and carry the org mark from the backend's {@code krt.png}; the frontend
   * copies were unreferenced and are deleted (FE-PERF-04), so a copy-paste of an old header would
   * render a broken image that only a browser notices.
   */
  @Test
  void homePage_ShouldNotFallBackToTheOrgMarkOrOrgFavicon() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("logos/krt.webp"))))
        .andExpect(content().string(not(containsString("logos/krt-favicon.webp"))));
  }

  /**
   * A {@code th:src} that points at a missing file renders a perfectly valid {@code <img>} tag and
   * fails only in the browser, where no server-side test looks. This pins the actual bytes onto the
   * classpath under the path the templates request.
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
   * Everything under {@code /logos/**} is public and cacheable, so an unreferenced file there is
   * dead weight every deploy ships and every crawler can fetch. FE-PERF-04 deleted seven of them
   * (about 570 KB, a 515 KB {@code sc.png} among them); only the Basetool logo family is left, and
   * a file outside it has to be added here deliberately rather than slip in.
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
