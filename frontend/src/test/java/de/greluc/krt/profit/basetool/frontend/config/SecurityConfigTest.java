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
import static org.mockito.Mockito.mock;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class SecurityConfigTest {

  @Test
  void userAuthoritiesMapper_shouldMapRealmRoles() {
    // Arrange
    RequestLoggingFilter loggingFilter = mock(RequestLoggingFilter.class);
    BackendRoleSyncFilter roleSyncFilter = mock(BackendRoleSyncFilter.class);
    BotProtectionFilter botProtectionFilter = mock(BotProtectionFilter.class);
    // M-15: SessionDebugFilter is now wired via ObjectProvider so prod (where the bean does not
    // exist) doesn't break. The userAuthoritiesMapper code path under test doesn't touch the
    // filter, so an empty mock provider is sufficient.
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<SessionDebugFilter> sessionDebugFilter =
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    SsoReAuthenticationEntryPoint ssoEntryPoint = mock(SsoReAuthenticationEntryPoint.class);
    CspNonceFilter cspNonceFilter = mock(CspNonceFilter.class);
    SecurityConfig config =
        new SecurityConfig(
            loggingFilter,
            roleSyncFilter,
            mock(TermsAcceptanceGateFilter.class),
            botProtectionFilter,
            sessionDebugFilter,
            ssoEntryPoint,
            cspNonceFilter,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    GrantedAuthoritiesMapper mapper = config.userAuthoritiesMapper();

    Map<String, Object> realmAccess = new HashMap<>();
    realmAccess.put("roles", List.of("Officer", "KRT Member", "admin"));

    Map<String, Object> claims = new HashMap<>();
    claims.put("realm_access", realmAccess);
    claims.put("sub", "user-id");

    OidcIdToken idToken = new OidcIdToken("token-value", null, null, claims);
    OidcUserAuthority oidcAuth = new OidcUserAuthority(idToken);

    // Act
    Collection<? extends GrantedAuthority> mappedAuthorities =
        mapper.mapAuthorities(List.of(oidcAuth));

    // Assert
    Set<String> authorityStrings =
        mappedAuthorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());

    assertTrue(authorityStrings.contains("ROLE_OFFICER"));
    assertTrue(authorityStrings.contains("ROLE_KRT_MEMBER"));
    assertTrue(authorityStrings.contains("ROLE_ADMIN"));
  }
}
