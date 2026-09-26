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

package de.greluc.krt.profit.basetool.frontend.logging;

import org.jetbrains.annotations.Nullable;

/**
 * Thread-local holder for the real client IP of the current request, which {@link
 * ClientIpRelayFilter} forwards to the backend as {@code X-Forwarded-For} so its per-IP rate limits
 * apply per client.
 *
 * <p>Set and cleared per request by {@link ClientIpContextFilter}; Reactor context propagation
 * carries it to the Netty threads.
 */
public final class ClientIpContext {

  private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

  private ClientIpContext() {}

  /**
   * Stores the given client IP for the calling thread; a {@code null}/blank value clears the slot.
   *
   * @param clientIp the resolved client IP, or {@code null}/blank to clear.
   */
  public static void set(@Nullable String clientIp) {
    if (clientIp == null || clientIp.isBlank()) {
      HOLDER.remove();
    } else {
      HOLDER.set(clientIp);
    }
  }

  /**
   * Returns the client IP stored for the current thread.
   *
   * @return the client IP, or {@code null} when none is bound.
   */
  @Nullable
  public static String get() {
    return HOLDER.get();
  }

  /** Removes the stored client IP - call from {@code finally} blocks to avoid leakage. */
  public static void clear() {
    HOLDER.remove();
  }
}
