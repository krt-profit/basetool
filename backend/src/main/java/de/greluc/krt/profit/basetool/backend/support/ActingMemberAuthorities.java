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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;

/**
 * The authorities to act with on behalf of a member, and the refusal when that member is not live
 * (ADR-0129).
 *
 * <p>Declared here, in the dependency-free leaf, for the same reason as {@link TermsConsentCheck}:
 * the filter that needs the answer lives in {@code config} and the answer lives in {@code service},
 * and ArchUnit rejects that edge (ADR-0047). Both packages point at this interface instead of at
 * each other.
 */
public interface ActingMemberAuthorities {

  /**
   * Assembles the authorities of the member a gateway request is acting for.
   *
   * <p>Implementations must fail <strong>closed</strong>: a subject with no local account is
   * refused rather than created, and a member the last roster sync no longer found in the identity
   * provider is refused outright. Without the second check a member disabled in Keycloak would keep
   * their full authority set here indefinitely — the database does not mirror account liveness —
   * and a named subject would outlive the revocation that a token expiry normally enforces.
   *
   * @param member the subject named in the on-behalf-of header
   * @return the member's authorities, assembled from the database
   * @throws AccessDeniedException when the member is unknown here or no longer live
   */
  @NotNull
  Collection<GrantedAuthority> authoritiesFor(@NotNull UUID member);
}
