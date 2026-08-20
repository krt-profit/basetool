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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins {@code GET /api/v1/users/me/memberships}, the me-scoped twin of {@code GET
 * /{id}/memberships} that the Android app's org-unit switcher reads (REQ-API-009 contract set).
 *
 * <p>Three properties are worth a test rather than a reading of the code:
 *
 * <ul>
 *   <li><b>It cannot name another user.</b> That is the whole reason it exists — the id-taking
 *       sibling would otherwise have to be reachable from the public API vhost, which is a
 *       default-deny allow-list precisely so no path able to name a third party sits on it. The
 *       caller's id comes from the JWT subject and from nowhere else.
 *   <li><b>An account with no roles still gets a 200.</b> {@code /api/v1/users/**} is gated on
 *       {@code hasRole('ADMIN')} in SecurityConfig, and a path that fell into that catch-all would
 *       403 every non-admin at the URL filter — that exact defect once blanked the web sidebar and
 *       is what {@code UserMembershipsSecurityTest} was written for. Here it would break the app's
 *       shell, which frames every screen.
 *   <li><b>{@code allKinds} means what it means on the sibling.</b> Two endpoints with the same
 *       parameter and different semantics is worse than one.
 * </ul>
 */
@SpringBootTest
class UserMeMembershipsTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @MockitoBean private JwtDecoder jwtDecoder;

  /** The caller's Keycloak subject; {@code UserService} parses it straight into the user id. */
  private final UUID caller = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(orgUnitMembershipQueryService.listOptionsForUser(any(UUID.class)))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "Staffel 1", "S1", OrgUnitKind.SQUADRON, true)));
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(any(UUID.class)))
        .thenReturn(List.of());
  }

  /**
   * Builds a JWT post-processor for the caller with the given authorities.
   *
   * @param authorities granted authorities, e.g. {@code ROLE_KRT_MEMBER}; may be empty.
   * @return the request post-processor.
   */
  private org.springframework.test.web.servlet.request.RequestPostProcessor callerJwt(
      String... authorities) {
    SimpleGrantedAuthority[] granted =
        java.util.Arrays.stream(authorities)
            .map(SimpleGrantedAuthority::new)
            .toArray(SimpleGrantedAuthority[]::new);
    return jwt().jwt(builder -> builder.subject(caller.toString())).authorities(granted);
  }

  @Test
  @DisplayName("the memberships resolved are the caller's own, taken from the JWT subject")
  void resolvesTheCallerFromTheToken() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me/memberships").with(callerJwt("ROLE_KRT_MEMBER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].orgUnitName").value("Staffel 1"));

    verify(orgUnitMembershipQueryService).listOptionsForUser(caller);
    verify(orgUnitMembershipQueryService, never()).listDirectMembershipOptions(any(UUID.class));
  }

  @Test
  @DisplayName("an authenticated account with no roles is served, not refused")
  void aRolelessAccountIsServed() throws Exception {
    // The app's switcher renders on the shell around every screen. A 403 here for a member whose
    // only fault is having no unit yet would break the frame rather than one list.
    mockMvc
        .perform(get("/api/v1/users/me/memberships").with(callerJwt()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("allKinds=true takes the all-four-kinds path, exactly as on the sibling endpoint")
  void allKindsSpansEveryKind() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/memberships")
                .param("allKinds", "true")
                .with(callerJwt("ROLE_KRT_MEMBER")))
        .andExpect(status().isOk());

    verify(orgUnitMembershipQueryService).listDirectMembershipOptions(caller);
    verify(orgUnitMembershipQueryService, never()).listOptionsForUser(any(UUID.class));
  }

  @Test
  @DisplayName("an unauthenticated caller is refused")
  void anonymousIsRefused() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/memberships")).andExpect(status().isUnauthorized());
  }
}
