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

package de.greluc.krt.profit.basetool.ingest.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code WWW-Authenticate} challenge of the exchange routes (RFC 9449 §7.1). */
public final class ExchangeChallenge {

  /** The proof algorithms the gateway accepts, as Spring's proof verifier does. */
  static final String ALGORITHMS = "ES256 ES384 ES512 RS256 RS384 RS512 PS256 PS384 PS512";

  /** Not instantiable. */
  private ExchangeChallenge() {}

  /**
   * Builds the challenge.
   *
   * @param error the OAuth error code, or {@code null} for none
   * @return the header value
   */
  public static @NotNull String header(@Nullable String error) {
    String challenge = "DPoP algs=\"" + ALGORITHMS + "\"";
    return error == null ? challenge : challenge + ", error=\"" + error + "\"";
  }
}
