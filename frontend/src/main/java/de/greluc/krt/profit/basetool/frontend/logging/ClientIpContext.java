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
 * Thread-local holder for the resolved real client IP of the current servlet request, so the {@code
 * WebClient} exchange filter ({@link ClientIpRelayFilter}) can forward it to the backend as {@code
 * X-Forwarded-For} from a Netty reactor thread that no longer has the Tomcat request bound.
 *
 * <p><b>Why this exists:</b> the backend is a pure resource server that no browser reaches directly
 * — every request is proxied server-side by this frontend. Without relaying the originating IP the
 * backend's per-IP rate limiter sees only the frontend container's single address and collapses
 * every per-client budget into one shared org-wide bucket, so a single caller can trip a public
 * endpoint's limit for everyone (security audit DOS-1). Populated by {@link ClientIpContextFilter}
 * at the start of every servlet request and cleared in the matching {@code finally} block to avoid
 * bleed-through on pooled / virtual threads. Mirrors {@link ActiveSquadronContext}; Reactor's
 * automatic context propagation carries the value across the hop to the reactor worker thread.
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
