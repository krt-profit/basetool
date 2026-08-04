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

import org.jetbrains.annotations.NotNull;

/**
 * An {@link org.springframework.security.core.Authentication} that carries an OIDC subject without
 * a token behind it.
 *
 * <p>Implemented by the acting-member authentication the ingest gateway's identity swap installs
 * (ADR-0129). {@link AuthenticatedSubject} needs some way to tell that apart from every other
 * token-less authentication, and the obvious candidate — {@code getName()} — is the one thing it
 * must <strong>not</strong> use: on a {@code UsernamePasswordAuthenticationToken} the name is the
 * member's callsign, and REQ-OBS-004 forbids that value from reaching a log line or an MDC field
 * because it is PII. A blanket name fallback would have leaked callsigns into the {@code userId}
 * field of every log line for such a caller.
 *
 * <p>So the promise is explicit rather than inferred: implementing this interface asserts that
 * {@link #subject()} is an OIDC {@code sub}, and nothing else opts in.
 */
public interface SubjectAuthentication {

  /**
   * The OIDC subject this authentication stands for.
   *
   * @return the non-blank {@code sub}, never a username, display name or callsign
   */
  @NotNull
  String subject();
}
