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

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Issues and checks the server nonces every exchange DPoP proof must carry (REQ-XCH-006, RFC 9449
 * §8).
 *
 * <p>A nonce is stateless: a time window and its HMAC under a key drawn at startup. A nonce of the
 * current or the previous window is accepted, so each one lives between {@link #WINDOW} and twice
 * that; a restart invalidates them all, and a client then retries once with the nonce of the
 * challenge.
 */
@Component
public class ExchangeDpopNonces {

  /** The length of one nonce window. */
  public static final Duration WINDOW = Duration.ofMinutes(5);

  private static final String HMAC = "HmacSHA256";
  private static final int KEY_BYTES = 32;
  private static final int WINDOW_BYTES = Long.BYTES;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;
  private final Clock clock;

  /** Creates the nonces on the system clock with a fresh random key. */
  public ExchangeDpopNonces() {
    this(Clock.systemUTC(), randomKey());
  }

  /**
   * Creates the nonces on the given clock and key, so a test can move time across a window.
   *
   * @param clock the time source
   * @param keyBytes the HMAC key
   */
  ExchangeDpopNonces(@NotNull Clock clock, byte @NotNull [] keyBytes) {
    this.clock = clock;
    this.key = new SecretKeySpec(keyBytes.clone(), HMAC);
  }

  /**
   * Returns the nonce of the current window.
   *
   * @return the nonce, URL-safe base64
   */
  public @NotNull String current() {
    return encode(window());
  }

  /**
   * Checks a nonce a proof carried.
   *
   * @param nonce the proof's {@code nonce} claim, or {@code null}
   * @return {@code true} when it belongs to the current or the previous window
   */
  public boolean isValid(@Nullable String nonce) {
    if (nonce == null || nonce.isBlank()) {
      return false;
    }
    byte[] presented;
    try {
      presented = DECODER.decode(nonce);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
    if (presented.length <= WINDOW_BYTES) {
      return false;
    }
    long window = ByteBuffer.wrap(presented, 0, WINDOW_BYTES).getLong();
    long now = window();
    if (window != now && window != now - 1) {
      return false;
    }
    return MessageDigest.isEqual(presented, raw(window));
  }

  /**
   * Returns the number of the current window.
   *
   * @return the window number
   */
  private long window() {
    return clock.instant().getEpochSecond() / WINDOW.toSeconds();
  }

  /**
   * Encodes one window's nonce.
   *
   * @param window the window number
   * @return the nonce
   */
  private @NotNull String encode(long window) {
    return ENCODER.encodeToString(raw(window));
  }

  /**
   * Builds the raw nonce of one window: the window number and its HMAC.
   *
   * @param window the window number
   * @return the bytes
   */
  private byte @NotNull [] raw(long window) {
    byte[] windowBytes = ByteBuffer.allocate(WINDOW_BYTES).putLong(window).array();
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      byte[] tag = mac.doFinal(windowBytes);
      return ByteBuffer.allocate(WINDOW_BYTES + tag.length).put(windowBytes).put(tag).array();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  /**
   * Draws a random HMAC key.
   *
   * @return the key bytes
   */
  private static byte @NotNull [] randomKey() {
    byte[] bytes = new byte[KEY_BYTES];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
