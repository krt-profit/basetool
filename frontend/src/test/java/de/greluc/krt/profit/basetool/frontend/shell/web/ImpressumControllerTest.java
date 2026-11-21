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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ImpressumControllerTest {

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
  void shouldReturnImpressumView() throws Exception {
    mockMvc
        .perform(get("/impressum"))
        .andExpect(status().isOk())
        .andExpect(view().name("shell/impressum"));
  }

  /**
   * The imprint follows the legal text pattern: the page head with the legal eyebrow, the text on a
   * card limited to the reading measure, and no HUD box.
   *
   * @throws Exception if the request fails
   */
  @Test
  void rendersTheTextPagePattern() throws Exception {
    mockMvc
        .perform(get("/impressum"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"page-head\"")))
        .andExpect(
            content().string(matchesPattern("(?s).*class=\"page-eyebrow\"[^>]*>Rechtliches<.*")))
        .andExpect(content().string(containsString("class=\"card legal-doc\"")))
        .andExpect(content().string(containsString("/css/shell/legal")))
        .andExpect(content().string(not(containsString("hud-box"))));
  }

  /**
   * The Impressum names the operator with the country, cites both disclosure statutes, carries the
   * DSA contact point with its languages and the fields of a notice, and links the source code and
   * the licence page (REQ-UI-040).
   *
   * @throws Exception if the request fails
   */
  @Test
  void rendersTheOperatorDisclosureAndTheDsaContactPoint() throws Exception {
    mockMvc
        .perform(get("/impressum"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("5 DDG und")))
        .andExpect(content().string(containsString("18 Abs. 1 MStV")))
        .andExpect(content().string(containsString("data-testid=\"impressum-provider\"")))
        .andExpect(content().string(containsString("Deutschland")))
        .andExpect(content().string(containsString("+49 170 9677429")))
        .andExpect(content().string(containsString("mailto:lucas.greuloch@gmail.com")))
        .andExpect(content().string(containsString("data-testid=\"impressum-dsa-contact\"")))
        .andExpect(content().string(containsString("(Art. 11 DSA)")))
        .andExpect(content().string(containsString("(Art. 12 DSA)")))
        .andExpect(content().string(containsString("Deutsch und Englisch")))
        .andExpect(content().string(containsString("data-testid=\"impressum-report-fields\"")))
        .andExpect(content().string(containsString("die genaue Fundstelle")))
        .andExpect(content().string(containsString("warum der Inhalt rechtswidrig ist")))
        .andExpect(content().string(containsString("der meldenden Person")))
        .andExpect(content().string(containsString("nach bestem Wissen")))
        .andExpect(content().string(containsString("https://github.com/krt-profit/basetool")))
        .andExpect(content().string(containsString("GPL-3.0-only")))
        .andExpect(
            content()
                .string(matchesPattern("(?s).*data-testid=\"impressum-licenses-link\"[^>]*>.*")))
        .andExpect(content().string(containsString("href=\"/licenses\"")));
  }

  /**
   * The Impressum carries none of the generator boilerplate: no representative for a natural
   * person, no consumer-dispute statement, no disclaimer citing the liability sections of the
   * former TMG, no generator credit.
   *
   * @throws Exception if the request fails
   */
  @Test
  void carriesNoGeneratorBoilerplate() throws Exception {
    mockMvc
        .perform(get("/impressum"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Vertreten durch"))))
        .andExpect(content().string(not(containsString("Streitbeilegung"))))
        .andExpect(content().string(not(containsString("Haftungsausschluss"))))
        .andExpect(content().string(not(containsString("8 bis 10 DDG"))))
        .andExpect(content().string(not(containsString("impressum-generator"))))
        .andExpect(content().string(not(containsString("kanzlei-hasselbach"))));
  }

  /**
   * The English page is titled „Legal notice" and cites the statutes by their German names.
   *
   * @throws Exception if the request fails
   */
  @Test
  void rendersTheEnglishLegalNotice() throws Exception {
    mockMvc
        .perform(get("/impressum").param("lang", "en"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("<title>Profit Basetool - Legal notice</title>")))
        .andExpect(content().string(containsString("Section 18(1) of the Medienstaatsvertrag")))
        .andExpect(content().string(containsString("Germany")))
        .andExpect(content().string(containsString("Single point of contact")))
        .andExpect(content().string(not(containsString("Imprint"))));
  }
}
