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
 * Assembles an acting member's authorities from the database, and refuses when that member is no
 * longer live (ADR-0129).
 *
 * <p>Reuses {@link CustomJwtGrantedAuthoritiesConverter#assembleFor(User)}, so a member acting
 * through the ingest gateway carries exactly the authority set they would carry logging in. That
 * reuse is the point: two assemblies would drift, and the drift would be invisible until someone
 * noticed the gateway path granting something different.
 *
 * <p><strong>Both refusals close a hole that only exists once a caller can name a subject instead
 * of presenting its token.</strong> The database does not mirror identity-provider liveness — the
 * roster sync fetches {@code enabled} and never persists it, and the {@code inKeycloak} flag it
 * does maintain is read by no authority code <em>outside this class</em>. A member removed or
 * deactivated in Keycloak therefore keeps {@code ACTIVE} and every role here until a sync says
 * otherwise.
 *
 * <p><strong>Presence and {@code enabled}, checked separately.</strong> {@code inKeycloak} answers
 * "did the last roster pass still see this account", {@code enabledInKeycloak} answers "was it
 * active when it did" (V230). Both had to be persisted before they could be read: the sync fetched
 * {@code enabled} and dropped it, so until V230 a deactivated member was refused nothing at all.
 * Without a token that gap is harmless — a disabled account cannot refresh, so its last access
 * token expires in minutes — and it stops being harmless the moment a caller can <em>name</em> a
 * subject, because a name never expires. Revocation now takes effect at the next sync pass, or
 * immediately on the member's next login, instead of never. While a token is what grants access
 * that is harmless: the account stops being issued tokens and the last one expires in minutes. A
 * named subject never expires, so without these checks the gateway could mint the authorities of a
 * revoked member — and ADR-0129's premise that a named subject cannot escalate beyond what that
 * member "could already do" would stop holding.
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
      // Deliberately NOT created. The login path creates a row for a first-seen subject, which is
      // right when a person authenticated; here nobody did, and inventing a member from a header
      // would turn that header into a registration primitive.
      log.warn("Refusing to act for a subject with no local account");
      throw new AccessDeniedException("The named member is not known here.");
    }
    User user = found.get();
    if (!user.isInKeycloak()) {
      log.warn("Refusing to act for a member the last roster sync no longer found in Keycloak");
      throw new AccessDeniedException("The named member is no longer active.");
    }
    if (!user.isEnabledInKeycloak()) {
      // Separate from the branch above on purpose: "deleted" and "deactivated" have different
      // remedies, and the log line is the only place the two are distinguishable — the answer to
      // the caller is byte-identical so the endpoint cannot be used to enumerate subjects.
      log.warn("Refusing to act for a member whose Keycloak account is disabled");
      throw new AccessDeniedException("The named member is no longer active.");
    }
    // Uncached, unlike the login path, which caches the same assembly per token (#1141). No cache
    // here on purpose: the natural key would be the member's id, and a header can name a different
    // member on every request, so the hit rate would be whatever the extractor population happens
    // to be while the entries pin authority sets for callers who are not currently calling. The
    // cost is one findById plus the assembler's own reads per ingest upload — an upload is already
    // a multi-second screenshot-extraction round trip, so this is not the expensive part of it.
    // Revisit only if ingest volume makes it one; correctness first, since a stale cached authority
    // set here would outlive a revoked member exactly the way the liveness check above refuses to.
    return authorityAssembler.assembleFor(user);
  }
}
