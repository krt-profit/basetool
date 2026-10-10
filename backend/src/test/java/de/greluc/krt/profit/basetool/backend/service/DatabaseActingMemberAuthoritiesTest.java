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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exchange.internal.DatabaseActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class DatabaseActingMemberAuthoritiesTest {

  private static final UUID MEMBER = UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000a1");

  @Mock private UserRepository userRepository;
  @Mock private CustomJwtGrantedAuthoritiesConverter authorityAssembler;
  @InjectMocks private DatabaseActingMemberAuthorities authorities;

  private User user;

  @BeforeEach
  void setUp() {
    user = new User();
    user.setId(MEMBER);
    user.setInKeycloak(true);
    user.setEnabledInKeycloak(true);
  }

  @Test
  void anAdminActsOnTheExchangeWithoutAnyStoredAuthority() {
    stored("ROLE_ADMIN", "USER_MANAGE", "ROLE_LOGISTICIAN");

    assertThat(names(authorities.exchangeAuthoritiesFor(MEMBER, List.of("exchange.connect"))))
        .containsExactly("ROLE_EXCHANGE_MEMBER", "XCH_CAPABILITY:exchange.connect");
  }

  @Test
  void aPendingMemberKeepsTheMarkerTheApprovalGateRefuses() {
    stored("ROLE_PENDING_APPROVAL");

    assertThat(names(authorities.exchangeAuthoritiesFor(MEMBER, List.of("exchange.connect"))))
        .containsExactly("ROLE_PENDING_APPROVAL");
  }

  @Test
  void aMemberWithoutARoleKeepsTheNoRoleMarker() {
    stored(Roles.NO_ROLE_MARKER);

    assertThat(names(authorities.exchangeAuthoritiesFor(MEMBER, List.of("exchange.connect"))))
        .containsExactly(Roles.NO_ROLE_MARKER);
  }

  @Test
  void anOffboardedMemberIsRefusedOnTheExchangeToo() {
    user.setEnabledInKeycloak(false);
    when(userRepository.findById(MEMBER)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> authorities.exchangeAuthoritiesFor(MEMBER, List.of()))
        .isInstanceOf(AccessDeniedException.class);
  }

  /**
   * Makes the member's stored authorities the given ones.
   *
   * @param names the authority names
   */
  private void stored(String @NotNull ... names) {
    when(userRepository.findById(MEMBER)).thenReturn(Optional.of(user));
    List<GrantedAuthority> granted =
        List.of(names).stream().map(n -> (GrantedAuthority) new SimpleGrantedAuthority(n)).toList();
    when(authorityAssembler.assembleFor(user)).thenReturn(granted);
  }

  /**
   * Maps authorities to their names.
   *
   * @param granted the authorities
   * @return the names in order
   */
  private static @NotNull List<String> names(@NotNull Collection<GrantedAuthority> granted) {
    return granted.stream().map(GrantedAuthority::getAuthority).toList();
  }
}
