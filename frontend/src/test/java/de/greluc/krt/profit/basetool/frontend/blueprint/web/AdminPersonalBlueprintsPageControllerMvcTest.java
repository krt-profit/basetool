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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level test for {@link AdminPersonalBlueprintsPageController}: the admin Blueprints page
 * renders for an ADMIN and is forbidden for a non-admin (the {@code hasRole('ADMIN')} gate).
 */
@SpringBootTest
class AdminPersonalBlueprintsPageControllerMvcTest {

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
  void view_withMember_rendersTheMembersDialogsTogetherWithTheImportScript() throws Exception {
    PageResponse<UserDto> empty = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(empty);
    String html =
        mockMvc
            .perform(
                get("/admin/personal-blueprints")
                    .param("userSub", "00000000-0000-0000-0000-000000000009"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("id=\"krt-bp-edit-modal\"")
        .contains("id=\"krt-bp-delete-modal\"")
        .contains("id=\"krt-bp-import-modal\"")
        .contains("personal-inventory-blueprints-import.js");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_rendersIdPlaceholderToken_inPerRowEndpoints() throws Exception {
    PageResponse<UserDto> empty = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(empty);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ID_PLACEHOLDER")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_fragmentResults_rendersOnlyTableFragment() throws Exception {
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight",
            "Arclight Pistol",
            UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"),
            "n",
            true,
            0L,
            null,
            null,
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1L, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009")
                .param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("blueprint/admin/personal-blueprints :: results"))
        .andExpect(content().string(containsString("Arclight Pistol")))
        .andExpect(content().string(not(containsString("id=\"bp-results\""))))
        .andExpect(content().string(not(containsString("id=\"krt-bp-edit-modal\""))))
        .andExpect(content().string(not(containsString("krt-admin-banner"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesMultiWordQueryAsUriVariable() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", userSub)
                .param("q", "Arclight Pistol")
                .param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(
            uriCaptor.capture(),
            anyTypeRef(),
            eq(UUID.fromString(userSub)),
            eq(200),
            qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Arclight Pistol", qCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page);

    String term = "Größe Röhre";
    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", userSub)
                .param("q", term)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(
            uriCaptor.capture(),
            anyTypeRef(),
            eq(UUID.fromString(userSub)),
            eq(200),
            qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }

  /**
   * The page follows the list pattern (REQ-UI-027): page head with the member eyebrow and count,
   * the member tab bar, a live search in the toolbar and the stacked table.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_withMember_rendersTheListPattern() throws Exception {
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight",
            "Arclight Pistol",
            UUID.randomUUID(),
            null,
            "n",
            true,
            0L,
            null,
            null,
            null,
            null,
            null);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(bp), 0, 200, 1L, 1, List.of()));

    String html =
        mockMvc
            .perform(
                get("/admin/personal-blueprints")
                    .param("userSub", "00000000-0000-0000-0000-000000000009")
                    .locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));

    assertThat(html)
        .containsPattern("class=\"page-eyebrow\"[^>]*>(?s:.*?)<span>Mitgliederverwaltung<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"bp-results\"")
        .contains("data-testid=\"member-tabs\"")
        .containsPattern("class=\"tab active\"[^>]*data-testid=\"member-tab-link-blueprints\"")
        .doesNotContain("krt-pi-userform")
        .doesNotContain("bp-purge")
        .contains("id=\"krt-bp-q\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-list-total=\"1\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krt-admin-banner")
        .doesNotContain("colspan");
    assertThat(main.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(2);
    assertThat(main).doesNotContain(">Filtern<");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void view_forbiddenForNonAdmin() throws Exception {
    mockMvc.perform(get("/admin/personal-blueprints")).andExpect(status().isForbidden());
  }

  /** Without a member, or with one that is not a UUID, the page leads back to the member list. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_withoutAValidMember_redirectsToTheMemberList() throws Exception {
    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/members"));
    mockMvc
        .perform(get("/admin/personal-blueprints").param("userSub", "../../etc/passwd"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/members"));
  }
}
