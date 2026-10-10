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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the ship dialog's insurance field (REQ-UI-027): a „Keine · Monate · LTI" segment with a
 * month field instead of a 122-option select, preselected server-side from the stored value {@code
 * 0}, {@code LTI} or a month count, which a hidden {@code insurance} field keeps carrying.
 */
@SpringBootTest
class HangarInsuranceFieldRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Builds a MockMvc over the full context with the security filter chain.
   *
   * @return the MockMvc
   */
  private @NotNull MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Posts the no-script add form without a ship type, so validation fails and the dialog is
   * re-rendered open with the submitted insurance value.
   *
   * @param insurance the submitted insurance value
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String rerenderWithInsurance(@NotNull String insurance) throws Exception {
    return mockMvc()
        .perform(
            post("/hangar/add").with(csrf()).param("insurance", insurance).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** The add dialog offers the three kinds, none preselected, and no month list. */
  @Test
  @WithMockUser
  void theAddDialogRendersTheSegmentWithNothingSelected() throws Exception {
    String html =
        mockMvc()
            .perform(get("/hangar").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"segment-insuranceKind-none\"")
        .contains("data-testid=\"segment-insuranceKind-months\"")
        .contains("data-testid=\"segment-insuranceKind-lti\"")
        .contains(">Keine<")
        .contains(">Monate<")
        .doesNotContainPattern("name=\"insuranceKind\" value=\"[A-Z]+\" checked")
        .containsPattern("id=\"ship-insurance-months-row\"[^>]*hidden")
        .containsPattern("<input type=\"hidden\" id=\"ship-insurance\"[^>]*name=\"insurance\"")
        .doesNotContain("120 Monate");
  }

  /** A stored month count selects „Monate" and fills and shows the month field. */
  @Test
  @WithMockUser
  void aMonthCountSelectsMonthsAndFillsTheField() throws Exception {
    String html = rerenderWithInsurance("24");

    assertThat(html)
        .containsPattern("name=\"insuranceKind\" value=\"MONTHS\" checked=\"checked\"")
        .doesNotContainPattern("name=\"insuranceKind\" value=\"(NONE|LTI)\" checked")
        .containsPattern("id=\"ship-insurance-months\"[^>]*value=\"24\"[^>]*required")
        .doesNotContainPattern("id=\"ship-insurance-months-row\"[^>]*hidden")
        .containsPattern("id=\"ship-insurance\"[^>]*value=\"24\"");
  }

  /** {@code LTI} selects „LTI" and keeps the month field hidden and empty. */
  @Test
  @WithMockUser
  void ltiSelectsLtiAndHidesTheMonthField() throws Exception {
    String html = rerenderWithInsurance("LTI");

    assertThat(html)
        .containsPattern("name=\"insuranceKind\" value=\"LTI\" checked=\"checked\"")
        .containsPattern("id=\"ship-insurance-months-row\"[^>]*hidden")
        .containsPattern("id=\"ship-insurance\"[^>]*value=\"LTI\"");
  }

  /** {@code 0} selects „Keine". */
  @Test
  @WithMockUser
  void zeroSelectsNone() throws Exception {
    String html = rerenderWithInsurance("0");

    assertThat(html)
        .containsPattern("name=\"insuranceKind\" value=\"NONE\" checked=\"checked\"")
        .containsPattern("id=\"ship-insurance\"[^>]*value=\"0\"");
  }
}
