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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link AdminAnnouncementPageController}'s AJAX twins: with {@code X-Requested-With}
 * the update and delete twins return {@code 200} (the update echoing the new {@code version});
 * without it the classic redirect handler runs. The page itself renders on the form pattern.
 */
@SpringBootTest
class AdminAnnouncementPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void updateAjax_withHeader_returns200AndEchoesVersion() throws Exception {
    when(backendApiClient.put(eq("/api/v1/announcement"), any(), eq(Void.class))).thenReturn(null);
    when(backendApiClient.get(contains("/announcement/admin"), anyTypeRef()))
        .thenReturn(Map.of("content", "x", "version", 7));

    mockMvc
        .perform(
            post("/admin/announcement/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("version")))
        .andExpect(content().string(containsString("7")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteAjax_withHeader_returns200() throws Exception {
    when(backendApiClient.delete(eq("/api/v1/announcement"), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/announcement/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void update_withoutHeader_redirects() throws Exception {
    when(backendApiClient.put(eq("/api/v1/announcement"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(post("/admin/announcement/update").with(csrf()).param("content", "x"))
        .andExpect(status().is3xxRedirection());
  }

  /**
   * The page renders on the form pattern: a page head under the "Benutzer &amp; Inhalte" eyebrow
   * with the delete as a danger entry of its overflow menu, the text as the numbered section of a
   * form card with the Markdown hint and a character counter seeded from the stored text, and one
   * sticky primary action.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void thePageRendersTheFormPattern() throws Exception {
    when(backendApiClient.get(contains("/announcement/admin"), anyTypeRef()))
        .thenReturn(Map.of("content", "Hallo **Welt**", "version", 3));

    String html =
        mockMvc
            .perform(get("/admin/announcement").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("<span class=\"page-eyebrow\"[^>]*>Benutzer &amp; Inhalte<")
        .contains("<h1>Information bearbeiten</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .doesNotContain("btn-outline-danger")
        .doesNotContain("flex-between")
        .containsPattern(
            "<button type=\"button\" id=\"trigger-delete-confirm\""
                + " class=\"overflow-menu__item overflow-menu__item--danger\" role=\"menuitem\"")
        .containsPattern("<form id=\"announcement-update-form\" class=\"form-layout card\"")
        .contains("1 \u00b7 Text</legend>")
        .containsPattern(
            "<textarea[^>]*id=\"info-edit-content\"[^>]*>Hallo \\*\\*Welt\\*\\*</textarea>")
        .contains("Markdown wird unterst\u00fctzt")
        .containsPattern(
            "id=\"info-edit-counter\"[^>]*data-template=\"\\{0\\} Zeichen\"[^>]*>14 Zeichen<")
        .containsPattern(
            "class=\"form-actions--sticky\">\\s*<button type=\"submit\" class=\"btn btn--cta\"")
        .contains("Information speichern")
        .contains("id=\"delete-confirm-modal\"");
  }

  /** Without a stored announcement the form renders empty with a zero counter. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void noAnnouncementRendersAnEmptyForm() throws Exception {
    mockMvc
        .perform(get("/admin/announcement").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"version\" value=\"0\"")))
        .andExpect(content().string(containsString(">0 Zeichen<")));
  }
}
