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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The „Open-Source-Lizenzen“ page (REQ-UI-021): public like the other legal pages (REQ-SEC-052),
 * rendered from the build-time report, and linked from the footer beside the terms.
 */
@SpringBootTest
class OssLicensesControllerTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void rendersForAnAnonymousVisitor() throws Exception {
    mockMvc
        .perform(get("/licenses"))
        .andExpect(status().isOk())
        .andExpect(view().name("licenses"))
        .andExpect(model().attribute("ossAvailable", true));
  }

  @Test
  void listsTheShippedComponentsGroupedByLicence() throws Exception {
    String html =
        mockMvc
            .perform(get("/licenses").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("Open-Source-Lizenzen")
        .contains("Apache License 2.0")
        .contains("SPDX: Apache-2.0")
        .contains("org.springframework:spring-core")
        .contains("SIL Open Font License 1.1")
        .contains("Lato")
        .doesNotContain("org.aspectj:aspectjweaver");
  }

  @Test
  void theFooterLinksThePageBesideTheTerms() throws Exception {
    String html =
        mockMvc
            .perform(get("/impressum"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int footer = html.indexOf("krt-footer-links");
    assertThat(footer).isPositive();
    String footerLinks = html.substring(footer, html.indexOf("</div>", footer));
    assertThat(footerLinks).contains("href=\"/terms\"").contains("href=\"/licenses\"");
    assertThat(footerLinks.indexOf("/licenses")).isGreaterThan(footerLinks.indexOf("/terms"));
  }
}
