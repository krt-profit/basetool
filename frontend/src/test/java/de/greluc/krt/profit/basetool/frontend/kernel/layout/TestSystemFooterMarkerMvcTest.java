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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The footer's test-system marker (REQ-UI-029): shown on every footer when {@code
 * app.instance.test-system} is on, absent with the production default. Rendered through the public
 * Impressum page, which an anonymous visitor reaches.
 */
@SpringBootTest
class TestSystemFooterMarkerMvcTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders the Impressum and returns the footer element's markup.
   *
   * @param locale the locale to render in, sent as the {@code KRT_LOCALE} cookie
   * @return the markup from the opening {@code <footer} to its closing tag
   * @throws Exception if the request fails
   */
  String footer(Locale locale) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String html =
        mockMvc
            .perform(get("/impressum").cookie(new Cookie("KRT_LOCALE", locale.toLanguageTag())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int start = html.indexOf("<footer");
    assertThat(start).as("the page renders the shared footer").isNotNegative();
    return html.substring(start, html.indexOf("</footer>", start));
  }

  @Test
  void productionShowsNoMarker() throws Exception {
    assertThat(footer(Locale.GERMAN))
        .startsWith("<footer class=\"krt-footer\">")
        .doesNotContain("krt-footer--test-system")
        .doesNotContain("krt-footer-instance")
        .doesNotContain("Testsystem");
  }

  /** The same page with the test-system setting on. */
  @Nested
  @TestPropertySource(properties = "app.instance.test-system=true")
  class OnTheTestSystem {

    @Test
    void theFooterCarriesTheGermanMarker() throws Exception {
      assertThat(footer(Locale.GERMAN))
          .startsWith("<footer class=\"krt-footer krt-footer--test-system\">")
          .contains("class=\"krt-footer-instance\" role=\"note\"")
          .contains("title=\"Dies ist das Testsystem, nicht das Live-System.")
          .contains("<span>Testsystem</span>");
    }

    @Test
    void theFooterCarriesTheEnglishMarker() throws Exception {
      assertThat(footer(Locale.ENGLISH))
          .contains("class=\"krt-footer-instance\"")
          .contains("<span>Test system</span>");
    }

    @Test
    void theMarkerComesBeforeTheLegalLinks() throws Exception {
      String footer = footer(Locale.GERMAN);
      assertThat(footer.indexOf("krt-footer-instance"))
          .isPositive()
          .isLessThan(footer.indexOf("krt-footer-links"));
    }
  }
}
