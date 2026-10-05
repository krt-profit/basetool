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

package de.greluc.krt.profit.basetool.backend.platform.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An {@link org.springframework.security.core.Authentication} that carries an OIDC subject without
 * a token behind it, such as the ingest gateway's acting-member authentication (ADR-0129).
 *
 * <p>Implementing it asserts that {@link #subject()} is an OIDC {@code sub}; {@link
 * AuthenticatedSubject} never falls back to {@code getName()}, which may be a callsign.
 */
public interface SubjectAuthentication {

  /**
   * The OIDC subject this authentication stands for.
   *
   * @return the non-blank {@code sub}, never a username, display name or callsign
   */
  @NotNull
  String subject();

  /**
   * The external client the request was relayed for, when there is one.
   *
   * @return the registry client id, or {@code null} when the request came from no external client
   */
  @Nullable
  default String externalClient() {
    return null;
  }

  /**
   * The DPoP key thumbprint of the installation the request was relayed for, when there is one.
   *
   * @return the thumbprint, or {@code null} when the request came from no external client
   */
  @Nullable
  default String exchangeInstallationKey() {
    return null;
  }

  /**
   * When the connection behind a relayed exchange request was made, as the gateway compared it with
   * a client disconnect: an offline token's {@code iat}, any other token's {@code auth_time}.
   *
   * @return the connection time in epoch seconds, or {@code null} when none was relayed
   */
  @Nullable
  default Long exchangeConnectedAt() {
    return null;
  }
}
