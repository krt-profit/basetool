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
 * The single HTTP client for all calls to the public Discord API, shared by {@link
 * DiscordIdentityProvider} and {@link DiscordMembershipChecker} to keep one connection pool.
 */
final class DiscordHttp {

  /** Connect and per-request timeout for every Discord call. */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** The shared client; default trust (Discord presents a publicly-trusted certificate). */
  static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  private DiscordHttp() {}
}
