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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = SecurityConfig.class)
@ActiveProfiles("test")
class RoleHierarchyTest {

  @MockitoBean private RequestLoggingFilter requestLoggingFilter;

  @MockitoBean private BackendRoleSyncFilter backendRoleSyncFilter;

  @MockitoBean private BotProtectionFilter botProtectionFilter;

  @MockitoBean private SessionDebugFilter sessionDebugFilter;

  @MockitoBean private SsoReAuthenticationEntryPoint ssoReAuthenticationEntryPoint;

  @MockitoBean private CspNonceFilter cspNonceFilter;

  @MockitoBean private MeterRegistry meterRegistry;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  // filterChain now injects the pool-hardened authorization_code token client (ADR-0115), which in
  // production comes from WebClientConfig; this SecurityConfig-only slice mocks it like the other
  // collaborators so the context still loads.
  @MockitoBean
  private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
      oauthAuthorizationCodeTokenResponseClient;

  @Autowired private RoleHierarchy roleHierarchy;

  @Test
  void officerShouldReachLogistician() {
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(
            List.of(new SimpleGrantedAuthority("ROLE_OFFICER")));

    assertTrue(
        reachable.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "ROLE_OFFICER should reach ROLE_LOGISTICIAN. Reachable: " + reachable);
  }

  @Test
  void adminShouldReachLogistician() {
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertTrue(
        reachable.stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOGISTICIAN")),
        "ROLE_ADMIN should reach ROLE_LOGISTICIAN. Reachable: " + reachable);
  }
}
