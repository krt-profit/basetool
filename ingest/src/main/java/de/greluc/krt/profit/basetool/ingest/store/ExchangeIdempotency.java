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

package de.greluc.krt.profit.basetool.ingest.store;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The exchange's idempotency cache in Redis (REQ-XCH-020): per client, member and key, the answer
 * to a write and the fingerprint of the request that produced it, kept for a day; and a claim on
 * the key, a Redis entry and no JVM lock, while the first request with a key is in flight, held
 * with a random token so only its holder releases it. Keys are hashed, so no client-chosen text
 * becomes part of a Redis key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeIdempotency {

  /** The key prefix of a cached answer. */
  public static final String PREFIX = "ingest:xch:idem:";

  /** The key prefix of a claim. */
  public static final String CLAIM_PREFIX = "ingest:xch:idem-lock:";

  /** The random bytes of a claim token. */
  static final int TOKEN_BYTES = 16;

  /** The characters of a claim token, URL-safe base64 without padding. */
  static final int TOKEN_LENGTH = 22;

  /** Deletes a claim only while it holds the caller's token. */
  private static final RedisScript<Long> RELEASE_CLAIM =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          end
          return 0
          """,
          Long.class);

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ExchangeStoreProperties properties;

  /**
   * Returns the namespace of a key: client, member and the key's hash.
   *
   * @param clientId the client
   * @param member the member
   * @param idempotencyKey the client's key
   * @return the namespace
   */
  public static @NotNull String namespace(
      @NotNull String clientId, @NotNull String member, @NotNull String idempotencyKey) {
    return clientId + ":" + member + ":" + sha256(idempotencyKey.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns a request's fingerprint.
   *
   * @param method the method
   * @param path the path
   * @param body the body
   * @return the SHA-256 of {@code method + " " + path + "\n"} followed by the body, hex
   */
  public static @NotNull String fingerprint(
      @NotNull String method, @NotNull String path, byte @NotNull [] body) {
    MessageDigest digest = sha256Digest();
    digest.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
    digest.update(body);
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Finds the cached answer of a namespace.
   *
   * @param namespace the namespace
   * @return the answer, or empty
   * @throws ExchangeUnavailableException if Redis cannot be read
   */
  public @NotNull Optional<Stored> find(@NotNull String namespace) {
    String json;
    try {
      json = redisTemplate.opsForValue().get(PREFIX + namespace);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    if (json == null) {
      return Optional.empty();
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      JsonNode contentType = node.get("contentType");
      return Optional.of(
          new Stored(
              node.get("fingerprint").stringValue(),
              node.get("status").intValue(),
              contentType != null && contentType.isString() ? contentType.stringValue() : null,
              node.get("body").stringValue()));
    } catch (RuntimeException e) {
      log.warn(
          "An idempotency entry is unreadable and is ignored: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Claims a namespace for the first request in flight with a Redis entry holding a token of its
   * own.
   *
   * @param namespace the namespace
   * @return the token this request holds the claim with, or empty when another request holds it
   * @throws ExchangeUnavailableException if Redis cannot be written
   */
  public @NotNull Optional<String> claim(@NotNull String namespace) {
    byte[] raw = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(raw);
    String token = TOKEN_ENCODER.encodeToString(raw);
    try {
      Boolean taken =
          redisTemplate
              .opsForValue()
              .setIfAbsent(CLAIM_PREFIX + namespace, token, properties.lockTtl());
      return Boolean.TRUE.equals(taken) ? Optional.of(token) : Optional.empty();
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  /**
   * Releases the claim on a namespace only while it still holds the given token, as one atomic
   * step; a failure is logged, since the claim expires on its own.
   *
   * @param namespace the namespace
   * @param token the token {@link #claim(String)} returned
   * @return {@code true} when the claim was this request's and is released
   */
  public boolean releaseClaim(@NotNull String namespace, @NotNull String token) {
    try {
      Long released =
          redisTemplate.execute(RELEASE_CLAIM, List.of(CLAIM_PREFIX + namespace), token);
      return released != null && released > 0L;
    } catch (RuntimeException e) {
      log.warn("An idempotency claim could not be released: {}", e.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * Returns the bytes a claim on a namespace occupies: its key and its token.
   *
   * @param namespace the namespace
   * @return the size in bytes
   */
  public static long claimBytes(@NotNull String namespace) {
    return (long) CLAIM_PREFIX.length() + namespace.length() + TOKEN_LENGTH;
  }

  /**
   * Returns the bytes an answer occupies once cached: its key and its stored value.
   *
   * @param namespace the namespace
   * @param stored the answer
   * @return the size in bytes
   */
  public int sizeOf(@NotNull String namespace, @NotNull Stored stored) {
    return json(stored).getBytes(StandardCharsets.UTF_8).length
        + PREFIX.length()
        + namespace.length();
  }

  /**
   * Caches an answer for its namespace.
   *
   * @param namespace the namespace
   * @param stored the answer
   * @throws ExchangeUnavailableException if Redis cannot be written
   */
  public void store(@NotNull String namespace, @NotNull Stored stored) {
    String json = json(stored);
    try {
      redisTemplate.opsForValue().set(PREFIX + namespace, json, properties.idempotencyTtl());
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  /**
   * Serializes an answer as it is cached.
   *
   * @param stored the answer
   * @return its JSON
   */
  private @NotNull String json(@NotNull Stored stored) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("fingerprint", stored.fingerprint());
    node.put("status", stored.status());
    node.put("contentType", stored.contentType());
    node.put("body", stored.body());
    return objectMapper.writeValueAsString(node);
  }

  /**
   * Wraps a Redis failure.
   *
   * @param cause the failure
   * @return the exception to throw
   */
  private static @NotNull ExchangeUnavailableException unavailable(
      @NotNull RuntimeException cause) {
    log.warn("Exchange idempotency store failed: {}", cause.getClass().getSimpleName());
    return new ExchangeUnavailableException("The idempotency store cannot be reached.", cause);
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return the SHA-256, hex
   */
  private static @NotNull String sha256(byte @NotNull [] bytes) {
    return HexFormat.of().formatHex(sha256Digest().digest(bytes));
  }

  /**
   * Returns a fresh SHA-256 digest.
   *
   * @return the digest
   * @throws IllegalStateException if the JVM offers no SHA-256
   */
  private static @NotNull MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * A cached answer.
   *
   * @param fingerprint the fingerprint of the request that produced it
   * @param status the status
   * @param contentType the content type
   * @param body the body
   */
  public record Stored(
      @NotNull String fingerprint,
      int status,
      @Nullable String contentType,
      @NotNull String body) {}
}
