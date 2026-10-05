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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render test for the member-edit page: the in-place save gate and the GET-safe
 * field-error slots (REQ-FE-007), and the form pattern with the Stammdaten / Mitgliedschaften /
 * Datenauskunft tabs (REQ-UI-027).
 */
@SpringBootTest
class MemberEditPageRenderTest {

  private static final UUID SK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Stubs the backend with the member {@code Pilot} and returns its id.
   *
   * @return the stubbed member's id
   */
  private @NotNull UUID stubMember() {
    UUID id = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/users/{id}"), eq(UserDto.class), eq(id)))
        .thenReturn(
            new UserDto(
                id,
                "pilot",
                "Pilot",
                "Pilot",
                "pilot@example.com",
                5,
                "desc",
                Set.of("ROLE_KRT_MEMBER"),
                Set.of(),
                null,
                false,
                false,
                true,
                null,
                java.util.List.of(),
                1L,
                null,
                false));
    return id;
  }

  /**
   * Renders the edit page in German.
   *
   * @param id the member id
   * @param source the {@code source} parameter
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull UUID id, @NotNull String source) throws Exception {
    return mockMvc
        .perform(get("/members/{id}/edit", id).param("source", source).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void editPage_rendersGetSafeFieldErrorSlotsAndInPlaceGate() throws Exception {
    UUID id = stubMember();

    String html =
        mockMvc
            .perform(get("/members/{id}/edit", id))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("in-place save gate present").contains("data-member-edit=\"true\"");
    assertThat(html)
        .as("displayName error slot present + GET-safe (no EL1011E thrown)")
        .contains("data-error-for=\"displayName\"");
    assertThat(html)
        .as("description error slot present")
        .contains("data-error-for=\"description\"");
  }

  /** The page renders on the form pattern with three tabs and one sticky primary action. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void editPage_rendersTheFormPatternWithTabs() throws Exception {
    UUID id = stubMember();
    when(backendApiClient.get(eq("/api/v1/users/{id}/memberships"), anyTypeRef(), eq(id)))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    SK_ID, "Sondereinheit", "SE", "SPECIAL_COMMAND", Boolean.FALSE)));

    String html = render(id, "members");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/members\"")
        .contains("<span>Mitgliederverwaltung</span>")
        .contains("<h1>Pilot</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-");
    assertThat(html)
        .contains("class=\"tab-nav member-edit-tabs\" role=\"tablist\"")
        .contains("data-testid=\"member-tab-profile\"")
        .contains("data-testid=\"member-tab-memberships\"")
        .contains("data-testid=\"member-tab-export\"")
        .contains("id=\"member-panel-profile\" class=\"member-edit-panel\" role=\"tabpanel\"")
        .containsPattern("id=\"member-panel-memberships\"[^>]*hidden")
        .containsPattern("id=\"member-panel-export\"[^>]*hidden");
    assertThat(html)
        .contains("id=\"member-edit-form\" data-member-edit=\"true\" class=\"form-layout card\"")
        .contains("class=\"form-section__head\"")
        .contains("maxlength=\"2000\"")
        .containsPattern("<span data-char-count>4</span> / 2000")
        .contains("name=\"version\"")
        .contains("class=\"form-actions--sticky\"")
        .contains("data-testid=\"member-edit-submit\"");
    assertThat(html.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(2);
    assertThat(html)
        .contains("class=\"data-table data-table--stack\" data-testid=\"member-memberships-table\"")
        .containsPattern("class=\"chip chip--muted\"\\s*>SK</span>")
        .doesNotContain(">SPECIAL_COMMAND<")
        .contains("href=\"/organisation/special-commands/" + SK_ID + "\"")
        .contains("data-testid=\"member-export-pdf\"")
        .contains("data-testid=\"member-export-json\"");
  }

  /** Without memberships the Mitgliedschaften tab shows the empty state instead of a table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void editPage_rendersTheMembershipEmptyState() throws Exception {
    UUID id = stubMember();
    when(backendApiClient.get(eq("/api/v1/users/{id}/memberships"), anyTypeRef(), eq(id)))
        .thenReturn(List.of());

    String html = render(id, "members");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine OrgUnit-Mitgliedschaften.")
        .doesNotContain("member-memberships-table");
  }

  /** Coming from the profile, the eyebrow leads back to the profile. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void editPage_eyebrowReturnsToTheProfile() throws Exception {
    UUID id = stubMember();

    assertThat(render(id, "profile"))
        .containsPattern("class=\"page-eyebrow\" href=\"/profile\"")
        .contains("<a href=\"/profile\" class=\"btn btn-ghost\">Abbrechen</a>");
  }
}
