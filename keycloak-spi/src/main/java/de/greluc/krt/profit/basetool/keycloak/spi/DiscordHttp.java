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

package de.greluc.krt.profit.basetool.keycloak.spi;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one HTTP client every call to the public Discord API goes through: the profile read and the
 * guild-display-name capture in {@link DiscordIdentityProvider}, and the guild-member read in the
 * first-login gate ({@link DiscordMembershipChecker}).
 *
 * <p>There used to be three of them, each built with the same connect timeout: one static in the
 * identity provider, and two in the authenticator factory for the membership checker and the
 * nickname reader. A {@link HttpClient} owns a connection pool and a selector thread, so three
 * clients meant three pools to the same host, none of them reusing another's connection. One shared
 * client keeps a single warm pool to {@code discord.com} for the whole Keycloak JVM.
 *
 * <p>The backend account-existence precheck keeps its own client ({@code BackendTrustSupport}): it
 * talks to a different host with a pinned, self-signed trust anchor, which must never leak into the
 * client that talks to the public Discord API.
 */
final class DiscordHttp {

  /** Connect and per-request timeout for every Discord call. */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** The shared client; default trust (Discord presents a publicly-trusted certificate). */
  static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  private DiscordHttp() {
    // Constant holder — not instantiable.
  }
}
