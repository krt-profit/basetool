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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * Pins the Star Citizen Fan Kit compliance band (REQ-UI-018) to the home page.
 *
 * <p>Section 2b of the Fan Kit Guidelines allows the required CIG trademark notice to sit "on the
 * home page, on a navigation area that is always visible regardless of scrolling, or both". The
 * band used to live in the always-visible fixed footer; it was moved to the end of the home page's
 * {@code <main>} so the footer stops spending a full row on it. That makes {@code GET /} the single
 * surface still carrying the notice — if this test fails, the app is out of compliance, not merely
 * out of layout.
 *
 * <p>Section 2 additionally couples the two elements: whoever renders the "Made By The Community"
 * logo must render the trademark notice as well. Both assertions therefore live in one test rather
 * than in two that could be disabled independently.
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

    // Anonymous home() path: null is a valid "no upcoming missions" response for the
    // next-7-days search and keeps the rendered page to its empty-state branch.
    when(backendApiClient.get(startsWith("/api/v1/missions/search"), anyTypeRef(), anyBoolean()))
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
        .andExpect(content().string(containsString(REQUIRED_TRADEMARK_NOTICE)));
  }

  /**
   * The notice is a prescribed legal wording, not UI copy: it must stay verbatim English in every
   * locale bundle. A well-meaning German translation of "are registered trademarks of" would break
   * section 2b while leaving every key-parity check green, so the value itself is asserted here.
   */
  @Test
  void trademarkNotice_ShouldStayVerbatimEnglish_InEveryLocale() {
    assertThat(messageSource.getMessage("fankit.trademark", null, Locale.GERMAN))
        .as("German bundle must not translate the prescribed trademark notice")
        .isEqualTo(REQUIRED_TRADEMARK_NOTICE);
    assertThat(messageSource.getMessage("fankit.trademark", null, Locale.ENGLISH))
        .as("English bundle must carry the prescribed trademark notice")
        .isEqualTo(REQUIRED_TRADEMARK_NOTICE);
  }
}
