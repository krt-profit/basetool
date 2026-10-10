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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles an acting member's reduced exchange authorities, checked against the stored ones from
 * {@link CustomJwtGrantedAuthoritiesConverter#assembleFor(User)}, and refuses a member who is no
 * longer present or enabled in Keycloak (ADR-0129).
 *
 * <p>The liveness checks rely on the persisted {@code inKeycloak} and {@code enabledInKeycloak}
 * flags, so a revocation takes effect at the next roster sync or the member's next login.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseActingMemberAuthorities implements ActingMemberAuthorities {

  /** The marker authority of an account awaiting approval, which the approval gate refuses. */
  private static final String PENDING_APPROVAL = "ROLE_PENDING_APPROVAL";

  private final UserRepository userRepository;
  private final CustomJwtGrantedAuthoritiesConverter authorityAssembler;

  /**
   * Assembles the member's stored authorities, refusing a member who is unknown here or no longer
   * live.
   *
   * @param member the subject named in the on-behalf-of header
   * @return the member's authorities, assembled from the database
   * @throws AccessDeniedException when the member is unknown here or no longer live
   */
  private @NotNull Collection<GrantedAuthority> storedAuthoritiesFor(@NotNull UUID member) {
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

  @Override
  @Transactional(readOnly = true)
  public @NotNull Collection<GrantedAuthority> exchangeAuthoritiesFor(
      @NotNull UUID member, @NotNull Collection<String> capabilityScopes) {
    Collection<GrantedAuthority> stored = storedAuthoritiesFor(member);
    boolean gated =
        stored.stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(
                authority ->
                    PENDING_APPROVAL.equals(authority) || Roles.NO_ROLE_MARKER.equals(authority));
    if (gated) {
      return stored;
    }
    List<GrantedAuthority> reduced = new ArrayList<>();
    reduced.add(new SimpleGrantedAuthority(Roles.authority(Roles.EXCHANGE_MEMBER)));
    capabilityScopes.stream()
        .distinct()
        .sorted()
        .map(scope -> new SimpleGrantedAuthority(Roles.EXCHANGE_CAPABILITY_PREFIX + scope))
        .forEach(reduced::add);
    return List.copyOf(reduced);
  }
}
