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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.identity.model.MyBlueprintSharingResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyPayoutPreferenceResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyRsiHandleResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the profile as a settings page on the form pattern (REQ-UI-027): page head with the
 * „Persönlich" eyebrow, a section navigation, four numbered sections that keep their own forms and
 * endpoints, one hidden save bar as the only primary action, and the security, export and deletion
 * parts as action cards without a form.
 */
@SpringBootTest
class ProfileSettingsPatternRenderTest {

  /** A section's no-script submit button, the form post beside the in-place save. */
  private static final Pattern NOSCRIPT_SAVE =
      Pattern.compile("<noscript>\\s*<button type=\"submit\"");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /profile} in German for a member with a description, the {@code DONATE} payout
   * preference and a stored RSI handle.
   *
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render() throws Exception {
    when(backendApiClient.get("/api/v1/users/me", UserDto.class))
        .thenReturn(
            new UserDto(
                UUID.fromString("3c9d8e7f-1a2b-4c3d-9e4f-5a6b7c8d9e0f"),
                null,
                "Valk",
                "Valk",
                null,
                null,
                "Hallo",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                7L,
                null,
                null));
    when(backendApiClient.get(
            "/api/v1/users/me/payout-preference", MyPayoutPreferenceResponse.class))
        .thenReturn(new MyPayoutPreferenceResponse("DONATE", 7L));
    when(backendApiClient.get(
            "/api/v1/users/me/blueprint-sharing", MyBlueprintSharingResponse.class))
        .thenReturn(new MyBlueprintSharingResponse(true, 7L));
    when(backendApiClient.get("/api/v1/users/me/rsi-handle", MyRsiHandleResponse.class))
        .thenReturn(new MyRsiHandleResponse("Valk_RSI", 7L));
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/profile").with(oidcLogin()).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** The head, the navigation and the action cards follow the pattern, with one primary action. */
  @Test
  void rendersTheSettingsPage() throws Exception {
    String html = render();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Persönlich<")
        .containsPattern("<h1>Benutzerprofil</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("pf-title")
        .doesNotContain("krtm-border-color-var-color-warning");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"profile-section-nav\"")
        .contains("href=\"#profile-section-identity\"")
        .contains("href=\"#profile-section-rsi\"")
        .contains("href=\"#profile-section-payout\"")
        .contains("href=\"#profile-section-blueprints\"")
        .contains("href=\"#profile-security-card\"")
        .contains("href=\"#profile-export-card\"")
        .contains("href=\"#profile-deletion-card\"")
        .contains("id=\"profile-security-card\"")
        .contains("data-testid=\"profile-export-pdf\"")
        .contains("id=\"profile-deletion-card\"");
  }

  /** The four sections keep their forms and endpoints and share one save bar that starts hidden. */
  @Test
  void rendersFourSectionsAndOneHiddenSaveBar() throws Exception {
    String html = render();

    assertThat(html)
        .contains("class=\"form-layout card profile-settings\"")
        .containsPattern("id=\"profile-description-form\"[^>]*action=\"/profile/description\"")
        .containsPattern("id=\"profile-rsi-handle-form\"[^>]*action=\"/profile/rsi-handle\"")
        .containsPattern("id=\"profile-payout-form\"[^>]*action=\"/profile/payout-preference\"")
        .containsPattern(
            "id=\"profile-blueprint-sharing-form\"[^>]*action=\"/profile/blueprint-sharing\"")
        .contains(">1 · Profil<")
        .contains(">2 · RSI-Handle<")
        .contains(">3 · Standard-Auszahlungsart<")
        .contains(">4 · Blaupausen-Verfügbarkeit<");
    assertThat(html.split("class=\"form-section\"", -1)).hasSize(5);
    assertThat(NOSCRIPT_SAVE.matcher(html).results().count()).isEqualTo(4);
    assertThat(html)
        .containsPattern("class=\"form-actions--sticky profile-save-bar\"[^>]*hidden")
        .contains("data-testid=\"profile-save\"")
        .contains("Änderungen speichern")
        .contains("data-testid=\"profile-discard\"")
        .contains("data-prefix=\"Ungespeichert\"")
        .doesNotContain("data-testid=\"profile-rsi-handle-save\"");
  }

  /** The stored values preselect the fields; the payout preference is a segmented control. */
  @Test
  void preselectsTheStoredValues() throws Exception {
    String html = render();

    assertThat(html)
        .contains("data-testid=\"segment-defaultPayoutPreference-payout\"")
        .containsPattern("name=\"defaultPayoutPreference\" value=\"DONATE\" checked=\"checked\"")
        .doesNotContain("payout-select")
        .doesNotContainPattern("<select[^>]*defaultPayoutPreference")
        .contains("value=\"Valk_RSI\"")
        .containsPattern("id=\"shareBlueprintsGlobally\"[^>]*checked")
        .contains("5 / 2000")
        .containsPattern("name=\"version\"[^>]*value=\"7\"");
  }
}
