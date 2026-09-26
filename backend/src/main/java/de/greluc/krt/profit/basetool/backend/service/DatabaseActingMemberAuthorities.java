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

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberAuthorities;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles an acting member's authorities from the database via {@link
 * CustomJwtGrantedAuthoritiesConverter#assembleFor(User)}, and refuses a member who is no longer
 * present or enabled in Keycloak (ADR-0129).
 *
 * <p>The liveness checks rely on the persisted {@code inKeycloak} and {@code enabledInKeycloak}
 * flags, so a revocation takes effect at the next roster sync or the member's next login.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseActingMemberAuthorities implements ActingMemberAuthorities {

  private final UserRepository userRepository;
  private final CustomJwtGrantedAuthoritiesConverter authorityAssembler;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Collection<GrantedAuthority> authoritiesFor(@NotNull UUID member) {
    Optional<User> found = userRepository.findById(member);
    if (found.isEmpty()) {
      log.warn("Refusing to act for a subject with no local account");
      throw new AccessDeniedException("The named member is not known here.");
    }
    User user = found.get();
    if (!user.isInKeycloak()) {
      log.warn("Refusing to act for a member the last roster sync no longer found in Keycloak");
      throw new AccessDeniedException("The named member is no longer active.");
    }
    if (!user.isEnabledInKeycloak()) {
      log.warn("Refusing to act for a member whose Keycloak account is disabled");
      throw new AccessDeniedException("The named member is no longer active.");
    }
    return authorityAssembler.assembleFor(user);
  }
}
