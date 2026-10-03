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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the Einbuchen form on the form pattern (REQ-UI-027): page head whose eyebrow links back
 * to the inventory list named by {@code source}, numbered sections, the catalog-mode segment, the
 * allocation rows and one sticky primary action.
 */
@SpringBootTest
class InventoryInputFormPatternRenderTest {

  /** The middle dot between a section number and its title. */
  private static final String DOT = "\u00b7";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders {@code /inventory/input} in German as a member, with every catalog empty.
   *
   * @param source the {@code source} parameter, or {@code null} for none
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@Nullable String source) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(List.of());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    MockHttpServletRequestBuilder request =
        get("/inventory/input")
            .locale(Locale.GERMAN)
            .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    if (source != null) {
      request = request.param("source", source);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** The form renders on the pattern: head, sections, segment, rows, one sticky CTA. */
  @Test
  void rendersTheFormPattern() throws Exception {
    String html = render("my");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/inventory/my\"")
        .containsPattern("<h1>Lagereintrag einbuchen</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    assertThat(html)
        .contains("id=\"inventory-input-form\" class=\"form-layout card\"")
        .containsPattern("class=\"form-section__head\">1 " + DOT)
        .containsPattern("class=\"form-section__head\">2 " + DOT)
        .containsPattern("class=\"form-section__head\">3 " + DOT)
        .contains("class=\"segmented segmented--block segmented--lg\"")
        .contains("name=\"inventoryCatalogMode\" value=\"material\"")
        .containsPattern("value=\"material\"[^>]*checked")
        .contains("class=\"form-grid\"")
        .contains("class=\"alloc-table\"")
        .contains("class=\"btn btn-ghost alloc-add\" data-trigger=\"inv-input-add-order\"")
        .contains("id=\"inputAllocOver\" class=\"field-error\" role=\"alert\" hidden");
    assertThat(html.split("btn--cta", -1)).hasSizeLessThanOrEqualTo(2);
    assertThat(html)
        .containsPattern(
            "class=\"form-actions--sticky\">\\s*<a href=\"/inventory/my\""
                + " data-trigger=\"history-back\" class=\"btn btn-ghost\">Abbrechen</a>")
        .containsPattern("data-testid=\"inventory-input-submit\">.*<span>Einbuchen</span>");
  }

  /** The eyebrow follows the source: the global list for the admin, the overview otherwise. */
  @Test
  void eyebrowFollowsTheSource() throws Exception {
    assertThat(render("aggregated")).containsPattern("class=\"page-eyebrow\" href=\"/inventory\"");
    assertThat(render(null)).containsPattern("class=\"page-eyebrow\" href=\"/inventory/my\"");
  }
}
