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
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the Star Citizen Fan Kit compliance band (REQ-UI-018) to the home page: the "Made By The
 * Community" logo and the CIG trademark notice render together on {@code GET /}.
 */
@SpringBootTest
class FanKitComplianceMvcTest {

  /**
   * The trademark notice exactly as prescribed by Fan Kit Guidelines section 2b, including the
   * space before the third registered-trademark sign — the wording is quoted, not paraphrased, so a
   * "tidied up" message value fails the comparison instead of silently shipping.
   */
  private static final String REQUIRED_TRADEMARK_NOTICE =
      "Star Citizen®, Roberts Space Industries® and Cloud Imperium ®"
          + " are registered trademarks of Cloud Imperium Rights LLC";

  /**
   * The Fankit Agreement clause 2(g) notice, verbatim, including its double full stop after {@code
   * Ltd}, no space before any ® sign and the Oxford comma.
   */
  private static final String REQUIRED_AGREEMENT_NOTICE =
      "This site is not endorsed by or affiliated with the Cloud Imperium or Roberts Space"
          + " Industries group of companies. All game content and materials are copyright Cloud"
          + " Imperium Rights LLC and Cloud Imperium Rights Ltd.. Star Citizen®, Squadron"
          + " 42®, Roberts Space Industries®, and Cloud Imperium® are registered"
          + " trademarks of Cloud Imperium Rights LLC. All rights reserved.";

  @Autowired private WebApplicationContext context;

  @Autowired private MessageSource messageSource;

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
   * The home page is reachable without a login ({@code "/"} is permitAll in {@code
   * SecurityConfig}), which is what makes it a valid public placement under section 2b. An
   * anonymous {@code GET /} must therefore already carry the unmodified logo artwork and the
   * notice.
   */
  @Test
  void homePage_ShouldCarryFanKitLogoAndTrademarkNotice_ForAnonymousVisitor() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("images/made-by-the-community.png")))
        .andExpect(content().string(containsString(REQUIRED_TRADEMARK_NOTICE)))
        .andExpect(content().string(containsString(REQUIRED_AGREEMENT_NOTICE)));
  }

  /** The trademark notice stays verbatim English in every locale bundle. */
  @Test
  void trademarkNotice_ShouldStayVerbatimEnglish_InEveryLocale() {
    assertThat(messageSource.getMessage("fankit.trademark", null, Locale.GERMAN))
        .as("German bundle must not translate the prescribed trademark notice")
        .isEqualTo(REQUIRED_TRADEMARK_NOTICE);
    assertThat(messageSource.getMessage("fankit.trademark", null, Locale.ENGLISH))
        .as("English bundle must carry the prescribed trademark notice")
        .isEqualTo(REQUIRED_TRADEMARK_NOTICE);
  }

  /**
   * The legal pages carry the Fan Kit band too, checked anonymously.
   *
   * @param path the legal page to check.
   * @throws Exception when the request fails.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"/privacy", "/impressum"})
  void legalPages_ShouldCarryTheBandAsAnAddition(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("images/made-by-the-community.png")))
        .andExpect(content().string(containsString(REQUIRED_TRADEMARK_NOTICE)))
        .andExpect(content().string(containsString(REQUIRED_AGREEMENT_NOTICE)));
  }

  /**
   * The Agreement notice is prescribed legal wording as well, and is asserted the same way: a
   * German rendering of "not endorsed by or affiliated with" would breach clause 2(g) while leaving
   * every key-parity check green.
   */
  @Test
  void agreementNotice_ShouldStayVerbatimEnglish_InEveryLocale() {
    assertThat(messageSource.getMessage("fankit.disclaimer", null, Locale.GERMAN))
        .as("German bundle must not translate the prescribed clause 2(g) notice")
        .isEqualTo(REQUIRED_AGREEMENT_NOTICE);
    assertThat(messageSource.getMessage("fankit.disclaimer", null, Locale.ENGLISH))
        .as("English bundle must carry the prescribed clause 2(g) notice")
        .isEqualTo(REQUIRED_AGREEMENT_NOTICE);
  }

  /**
   * The two notices are not interchangeable and must not drift into each other.
   *
   * <p>The tempting "cleanup" is to give both the same spacing before ® or to fold one into the
   * other. Either would leave a plausible-looking band that satisfies neither document, so the
   * difference itself is asserted rather than left to a reviewer's eye.
   */
  @Test
  void theTwoNotices_ShouldKeepTheirDifferingRegisteredSignSpacing() {
    assertThat(REQUIRED_TRADEMARK_NOTICE)
        .as("§2b prose carries a space before its third registered sign")
        .contains("Cloud Imperium ®");
    assertThat(REQUIRED_AGREEMENT_NOTICE)
        .as("clause 2(g) carries no space before any registered sign")
        .doesNotContain(" ®");
    assertThat(REQUIRED_AGREEMENT_NOTICE)
        .as("clause 2(g) writes Ltd with two full stops")
        .contains("Cloud Imperium Rights Ltd.. Star Citizen");
  }
}
