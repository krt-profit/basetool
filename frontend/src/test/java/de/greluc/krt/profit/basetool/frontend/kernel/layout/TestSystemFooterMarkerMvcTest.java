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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

/**
 * The footer's test-system marker (REQ-UI-029): absent on a real page with the production default,
 * and present in the shared footer fragment whenever the {@code testSystem} flag is on.
 */
@SpringBootTest
class TestSystemFooterMarkerMvcTest {

  @Autowired private WebApplicationContext context;

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders the public Impressum with the configured default and returns its footer markup.
   *
   * @return the markup from the opening {@code <footer} to its closing tag
   * @throws Exception if the request fails
   */
  private String impressumFooter() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String html =
        mockMvc
            .perform(get("/impressum").cookie(new Cookie("KRT_LOCALE", "de")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int start = html.indexOf("<footer");
    assertThat(start).as("the page renders the shared footer").isNotNegative();
    return html.substring(start, html.indexOf("</footer>", start));
  }

  /**
   * Renders the shared footer fragment as a test-system page would.
   *
   * @param locale the locale to render in
   * @return the rendered footer markup
   */
  private String testSystemFooter(Locale locale) {
    ServletContext servletContext = context.getServletContext();
    WebContext webContext =
        new WebContext(
            JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(
                    new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
            locale);
    webContext.setVariable("testSystem", true);
    webContext.setVariable("appVersion", "1.0.0");
    return templateEngine.process("kernel/fragments/footer", Set.of("footer"), webContext);
  }

  @Test
  void productionShowsNoMarker() throws Exception {
    assertThat(impressumFooter())
        .startsWith("<footer class=\"krt-footer\">")
        .doesNotContain("krt-footer--test-system")
        .doesNotContain("krt-footer-instance")
        .doesNotContain("Testsystem");
  }

  @Test
  void theTestSystemFooterCarriesTheGermanMarker() {
    assertThat(testSystemFooter(Locale.GERMAN))
        .contains("<footer class=\"krt-footer krt-footer--test-system\">")
        .contains("class=\"krt-footer-instance\" role=\"note\"")
        .contains("title=\"Dies ist das Testsystem, nicht das Live-System.")
        .contains("<span>Testsystem</span>");
  }

  @Test
  void theTestSystemFooterCarriesTheEnglishMarker() {
    assertThat(testSystemFooter(Locale.ENGLISH))
        .contains("class=\"krt-footer-instance\"")
        .contains("<span>Test system</span>");
  }

  @Test
  void theMarkerComesBeforeTheLegalLinks() {
    String footer = testSystemFooter(Locale.GERMAN);
    assertThat(footer.indexOf("krt-footer-instance"))
        .isPositive()
        .isLessThan(footer.indexOf("krt-footer-links"));
  }
}
