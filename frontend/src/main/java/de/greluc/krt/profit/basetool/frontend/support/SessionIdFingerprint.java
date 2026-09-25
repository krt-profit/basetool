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

package de.greluc.krt.profit.basetool.frontend.support;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a session id into a short, stable fingerprint that is safe to write into a log line
 * (APPSEC-12).
 *
 * <p>A Spring session id is a bearer credential: whoever holds it holds the session, and with it
 * the user's OAuth2 tokens. Logging it verbatim put that credential into every log file and into
 * Loki, where it outlives the session's own retention and reaches everyone who can read logs. What
 * a log reader actually needs is to <em>correlate</em> lines of one session — "the same session
 * lost its authentication here" — and a truncated SHA-256 does that without being usable to resume
 * the session: the first {@value #FINGERPRINT_HEX_CHARS} hex characters (48 bits) keep accidental
 * collisions between the few hundred live sessions negligible, and a hash cannot be turned back
 * into a cookie value.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SessionIdFingerprint {

  /** Length of the fingerprint in hex characters: 12 characters, 48 bits of the digest. */
  static final int FINGERPRINT_HEX_CHARS = 12;

  /** What a log line shows when there is no session at all. */
  public static final String NONE = "none";

  /**
   * Fingerprints a raw session id.
   *
   * @param sessionId the raw session id, or {@code null} when there is none
   * @return the first {@value #FINGERPRINT_HEX_CHARS} lowercase hex characters of the id's SHA-256,
   *     or {@link #NONE} when {@code sessionId} is {@code null} or blank
   */
  @Contract(pure = true)
  public static @NotNull String of(@Nullable String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return NONE;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX_CHARS);
    } catch (NoSuchAlgorithmException e) {
      return NONE;
    }
  }

  /**
   * Fingerprints the id of a session.
   *
   * @param session the session, or {@code null} when the request has none
   * @return the session id's fingerprint (see {@link #of(String)}), or {@link #NONE} when {@code
   *     session} is {@code null}
   */
  public static @NotNull String of(@Nullable HttpSession session) {
    return session == null ? NONE : of(session.getId());
  }
}
