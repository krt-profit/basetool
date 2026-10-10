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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class PrivacyControllerTest {

  /** The public list of approved clients the notice links. */
  private static final String APPROVED_CLIENTS_URL =
      "https://github.com/krt-profit/basetool/blob/main/docs/legal/approved-clients.md";

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
  void shouldReturnPrivacyView() throws Exception {
    mockMvc.perform(get("/privacy")).andExpect(status().isOk()).andExpect(view().name("privacy"));
  }

  /**
   * The notice renders the connected-applications section with a working link to the public list of
   * approved clients, in both languages (REQ-XCH-002).
   *
   * @param lang the language requested through the {@code lang} parameter
   * @param heading the section heading expected in that language
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @CsvSource({"de,Verbundene Anwendungen", "en,Connected applications"})
  void rendersTheConnectedApplicationsSection(String lang, String heading) throws Exception {
    mockMvc
        .perform(get("/privacy").param("lang", lang))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<h3 id=\"privacy-3-10\">" + heading + "</h3>")))
        .andExpect(content().string(containsString("href=\"" + APPROVED_CLIENTS_URL + "\"")));
  }

  /**
   * The notice opens with a table of contents whose every entry jumps to a heading of the text, and
   * the text sits on a card under the legal page head (REQ-UI-027).
   *
   * @throws Exception if the request fails
   */
  @Test
  void rendersAJumpListToEveryHeading() throws Exception {
    String html =
        mockMvc
            .perform(get("/privacy").param("lang", "de"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"privacy-toc\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Rechtliches<")
        .contains("class=\"card legal-doc\"")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-");
    Matcher link = Pattern.compile("href=\"#(privacy-[0-9-]+)\"").matcher(html);
    int links = 0;
    while (link.find()) {
      links++;
      assertThat(html).as("target of #%s", link.group(1)).contains("id=\"" + link.group(1) + "\"");
    }
    assertThat(links).as("one jump link per section and subsection").isEqualTo(22);
  }
}
