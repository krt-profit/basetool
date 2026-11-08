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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;

/**
 * The login maps no role from the OIDC token: roles reach the session only through {@link
 * BackendRoleSyncFilter} from the backend's {@code /users/me} (REQ-SEC-088).
 */
class SecurityConfigTest {

  @Test
  void theSecurityConfigurationDeclaresNoTokenAuthoritiesMapper() {
    assertThat(Arrays.stream(SecurityConfig.class.getDeclaredMethods()).map(Method::getReturnType))
        .doesNotContain(GrantedAuthoritiesMapper.class);
  }
}
