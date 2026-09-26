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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the active-OrgUnit switcher endpoint {@code POST /me/active-org-unit} (FE-SEC-02): the
 * {@code _referer} redirect target is honoured only as a same-origin path, and a malformed {@code
 * orgUnitId} yields {@code 400}.
 */
@SpringBootTest
class MeFrontendControllerTest {

  private static final String ENDPOINT = "/me/active-org-unit";

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.example/phish",
        "//evil.example/phish",
        "/\\evil.example/phish",
        "javascript:alert(1)",
        "evil.example",
        "/missions\r\nSet-Cookie: x=1",
        ""
      })
  @WithMockUser
  void aRefererThatIsNotASameOriginPathFallsBackToTheRoot(String referer) throws Exception {
    mockMvc
        .perform(post(ENDPOINT).param("orgUnitId", "").param("_referer", referer).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/"));
  }

  @Test
  @WithMockUser
  void aSameOriginPathWithAQueryIsKept() throws Exception {
    mockMvc
        .perform(
            post(ENDPOINT).param("orgUnitId", "").param("_referer", "/missions?x=1").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/missions?x=1"));
  }

  @Test
  @WithMockUser
  void aMissingRefererRedirectsToTheRoot() throws Exception {
    mockMvc
        .perform(post(ENDPOINT).param("orgUnitId", "").with(csrf()))
        .andExpect(redirectedUrl("/"));
  }

  @Test
  @WithMockUser
  void anOrgUnitIdThatIsNotAUuidIsABadRequest() throws Exception {
    mockMvc
        .perform(
            post(ENDPOINT)
                .param("orgUnitId", "not-a-uuid")
                .param("_referer", "/missions")
                .with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser
  void aValidOrgUnitIdIsPinnedInTheSessionAndABlankOneClearsIt() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(
            post(ENDPOINT)
                .session(session)
                .param("orgUnitId", orgUnitId.toString())
                .param("_referer", "/missions")
                .with(csrf()))
        .andExpect(redirectedUrl("/missions"));
    assertEquals(
        orgUnitId.toString(),
        session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY));

    mockMvc
        .perform(
            post(ENDPOINT)
                .session(session)
                .param("orgUnitId", "")
                .param("_referer", "/missions")
                .with(csrf()))
        .andExpect(redirectedUrl("/missions"));
    assertNull(session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY));
  }

  @Test
  void safeRedirectTargetAcceptsOnlySingleSlashPaths() {
    assertEquals("/", MeFrontendController.safeRedirectTarget(null));
    assertEquals("/", MeFrontendController.safeRedirectTarget("//evil"));
    assertEquals("/", MeFrontendController.safeRedirectTarget("/\\evil"));
    assertEquals("/", MeFrontendController.safeRedirectTarget("https://evil"));
    assertEquals("/", MeFrontendController.safeRedirectTarget("javascript:x"));
    assertEquals("/", MeFrontendController.safeRedirectTarget("/a\tb"));
    assertEquals("/", MeFrontendController.safeRedirectTarget("/"));
    assertEquals("/missions?x=1", MeFrontendController.safeRedirectTarget("/missions?x=1"));
    assertEquals("/orders/1#top", MeFrontendController.safeRedirectTarget("/orders/1#top"));
  }
}
