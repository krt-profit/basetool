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

package de.greluc.krt.profit.basetool.ingest.handoff;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.problem.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Stages single-use browser handoffs in Redis (REQ-INGEST-003).
 *
 * <p>Entries are keyed {@code ingest:handoff:&lt;sub&gt;:&lt;handoffId&gt;}, hold a {@link
 * StagedHandoff} and expire after {@link IngestProperties#handoffTtl()}. The gateway only writes;
 * the frontend consumes them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffStagingService {

  /** Redis key prefix; the full key is {@code ingest:handoff:<sub>:<handoffId>}. */
  public static final String KEY_PREFIX = "ingest:handoff:";

  /**
   * Prefix of the index of a client's staged mass change, {@code
   * ingest:handoff-index:mass:<client>:<sub>}, a slot apart from the drafts and from other clients;
   * deliberately not under {@link #KEY_PREFIX}, so a wildcard sweep of staged handoffs cannot
   * mistake an index for one.
   */
  static final String MASS_CHANGE_INDEX_PREFIX = "ingest:handoff-index:mass:";

  /**
   * Prefix of the index of an exchange client's drafts, {@code
   * ingest:handoff-index:drafts:<client>:<sub>}, apart from other clients' drafts.
   */
  static final String DRAFT_INDEX_PREFIX = "ingest:handoff-index:drafts:";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final IngestProperties ingestProperties;

  /**
   * Stages an exchange client's draft for one-time pickup in slots of its own per client and
   * member, so no client evicts another client's drafts, and says how large it is so the exchange's
   * byte budget can count it (REQ-XCH-019).
   *
   * @param clientId the registry client that sent the draft
   * @param sub the member's subject
   * @param kind which draft is being staged
   * @param draftJson the backend draft response, stored verbatim
   * @param cap the most live drafts of this client for this member; the oldest are evicted
   * @return where it is staged and how large it is
   * @throws BadRequestException if the draft exceeds the handoff size cap
   */
  public @NotNull Staged stageDraft(
      @NotNull String clientId,
      @NotNull String sub,
      @NotNull HandoffKind kind,
      @NotNull String draftJson,
      int cap) {
    return store(
        sub,
        kind,
        draftJson,
        ingestProperties.maxHandoffBytes(),
        DRAFT_INDEX_PREFIX + clientId + ":" + sub,
        cap);
  }

  /**
   * Stages a client's change set the mass-change guard held back, in a slot of one per client and
   * member, so it never evicts a draft or another client's pending change set, and the same
   * client's newer one replaces it (REQ-XCH-021).
   *
   * @param clientId the registry client that sent the change set
   * @param sub the member's subject
   * @param changeJson the staged change set with its client, installation, resource and staging
   *     time
   * @param maxBytes the largest staged document
   * @return where it is staged and how large it is
   * @throws BadRequestException if the document exceeds {@code maxBytes}
   */
  public @NotNull Staged stageMassChange(
      @NotNull String clientId, @NotNull String sub, @NotNull String changeJson, long maxBytes) {
    return store(
        sub,
        HandoffKind.MASS_CHANGE,
        changeJson,
        maxBytes,
        MASS_CHANGE_INDEX_PREFIX + clientId + ":" + sub,
        1);
  }

  /**
   * Returns the size a handoff will be staged with, the same figure {@link Staged#bytes()} reports,
   * so a byte budget can reserve it before the value is written.
   *
   * @param kind the handoff's kind
   * @param json the document to stage
   * @return the staged value's size in bytes
   */
  public long stagedBytes(@NotNull HandoffKind kind, @NotNull String json) {
    return value(kind, json).getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Serializes a handoff as it is staged.
   *
   * @param kind the handoff's kind
   * @param json the document to stage
   * @return the stored value
   */
  private @NotNull String value(@NotNull HandoffKind kind, @NotNull String json) {
    return objectMapper.writeValueAsString(new StagedHandoff(kind, json));
  }

  /**
   * Stores one handoff under a fresh id of 160 bits of {@link SecureRandom} entropy, URL-safe
   * base64, and keeps its index within the cap.
   *
   * @param sub the subject
   * @param kind the handoff's kind
   * @param json the staged document
   * @param maxBytes the largest stored value
   * @param indexKey the subject's index for this kind of handoff
   * @param cap the most live entries in that index
   * @return where it is staged and how large it is
   * @throws BadRequestException if the value exceeds {@code maxBytes}
   */
  private @NotNull Staged store(
      @NotNull String sub,
      @NotNull HandoffKind kind,
      @NotNull String json,
      long maxBytes,
      @NotNull String indexKey,
      int cap) {
    byte[] raw = new byte[20];
    RANDOM.nextBytes(raw);
    String handoffId = URL_ENCODER.encodeToString(raw);
    String value = value(kind, json);

    long stagedBytes = value.getBytes(StandardCharsets.UTF_8).length;
    if (stagedBytes > maxBytes) {
      log.warn(
          "Refused to stage an oversized {} handoff (sub=u-{}, bytes={}, max={})",
          kind,
          mask(sub),
          stagedBytes,
          maxBytes);
      throw new BadRequestException("The import draft is too large to hand off.");
    }

    String key = key(sub, handoffId);
    redisTemplate.opsForValue().set(key, value, ingestProperties.handoffTtl());
    trimSubjectIndex(sub, handoffId, indexKey, cap);
    log.info(
        "Staged {} handoff (sub=u-{}, hid=h-{}, draftLen={}, ttl={})",
        kind,
        mask(sub),
        mask(handoffId),
        json.length(),
        ingestProperties.handoffTtl());
    return new Staged(handoffId, key, stagedBytes);
  }

  /**
   * Records the new handoff in the subject's index and evicts the oldest entries beyond the
   * per-subject cap, including their payload keys.
   *
   * <p>Best-effort: failures are logged and swallowed, leaving the TTL as the backstop.
   *
   * @param sub the caller's subject
   * @param handoffId the id just staged
   * @param indexKey the subject's index
   * @param cap the most live entries in it
   */
  private void trimSubjectIndex(
      @NotNull String sub, @NotNull String handoffId, @NotNull String indexKey, int cap) {
    try {
      Long size = redisTemplate.opsForList().rightPush(indexKey, handoffId);
      redisTemplate.expire(indexKey, ingestProperties.handoffTtl());
      long excess = size == null ? 0L : size - cap;
      for (long i = 0; i < excess; i++) {
        String evicted = redisTemplate.opsForList().leftPop(indexKey);
        if (evicted == null) {
          break;
        }
        redisTemplate.delete(key(sub, evicted));
      }
      if (excess > 0) {
        log.info(
            "Evicted {} handoff(s) over the per-subject cap (sub=u-{}, cap={})",
            excess,
            mask(sub),
            cap);
      }
    } catch (RuntimeException redisProblem) {
      log.warn(
          "Could not maintain the handoff index (sub=u-{}): {}",
          mask(sub),
          redisProblem.toString());
    }
  }

  /**
   * Builds the Redis key for a {@code (sub, handoffId)} pair.
   *
   * @param sub the caller's subject
   * @param handoffId the handoff id
   * @return the namespaced Redis key
   */
  private static @NotNull String key(@NotNull String sub, @NotNull String handoffId) {
    return KEY_PREFIX + sub + ":" + handoffId;
  }

  /**
   * Produces a short, non-reversible log token for a subject or handoff id (REQ-OBS-004), matching
   * the frontend's masking of the same input.
   *
   * @param value the subject or handoff id to mask; {@code null} yields the literal {@code "none"}
   * @return the lower-case hex of the value's hash, or {@code "none"} for a {@code null} input
   */
  private static @NotNull String mask(String value) {
    return value == null ? "none" : Integer.toHexString(value.hashCode());
  }

  /**
   * A staged handoff.
   *
   * @param handoffId the id the frontend picks it up by
   * @param key its Redis key
   * @param bytes the size of the stored value
   */
  public record Staged(@NotNull String handoffId, @NotNull String key, long bytes) {}
}
