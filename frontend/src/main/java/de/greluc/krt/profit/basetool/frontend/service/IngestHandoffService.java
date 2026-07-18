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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.StagedHandoff;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the one-click ingest handoff the gateway staged in the shared Redis (epic #639, {@code
 * REQ-INGEST-003/-004}). When the extractor opens {@code ?handoff=<id>}, the frontend consumes the
 * staged draft for {@code (session sub, handoffId)} — single-use, scoped to the browsing user — and
 * pre-fills the existing review surface.
 *
 * <p>The key schema is shared with the gateway: {@code ingest:handoff:<sub>:<handoffId>} → {@link
 * StagedHandoff} JSON. The read is an atomic {@code GETDEL}, so a stolen or replayed id is useless
 * and the entry is gone after the first successful pickup. Scoping the key by the session {@code
 * sub} means a foreign id never resolves to another user's draft (no IDOR). Every failure — unknown
 * / expired / consumed id, wrong kind, malformed value, or Redis being unreachable — degrades to
 * {@link Optional#empty()} so the page never errors out (it falls back to the normal empty form).
 * The session token is never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestHandoffService {

  /** Redis key prefix shared with the gateway; full key is {@code ingest:handoff:<sub>:<id>}. */
  private static final String KEY_PREFIX = "ingest:handoff:";

  /** The gateway mints 160-bit URL-safe base64 ids; reject anything outside that shape. */
  private static final Pattern HANDOFF_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * The auto-configured string Redis template (from {@code spring.data.redis.*}) — the same Redis
   * the gateway stages handoffs into and Spring Session uses, with the {@code
   * StringRedisSerializer} for both key and value so the gateway's entries round-trip
   * byte-for-byte.
   */
  private final StringRedisTemplate redisTemplate;

  /**
   * Atomically reads and deletes the staged draft for {@code (sub, handoffId)} and, when it is the
   * expected kind, deserialises its {@code draftJson} into {@code draftType}.
   *
   * @param sub the session user's Keycloak subject (scopes the lookup)
   * @param handoffId the id from the {@code ?handoff=} parameter
   * @param expectedKind the kind the calling surface can render
   * @param draftType the draft DTO class to deserialise into
   * @param <T> the draft DTO type
   * @return the parsed draft, or empty for any unknown / expired / wrong-kind / malformed / Redis
   *     failure
   */
  public <T> @NotNull Optional<T> consume(
      String sub,
      String handoffId,
      @NotNull HandoffKind expectedKind,
      @NotNull Class<T> draftType) {
    if (sub == null
        || sub.isBlank()
        || handoffId == null
        || !HANDOFF_ID.matcher(handoffId).matches()) {
      return Optional.empty();
    }
    try {
      String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + sub + ":" + handoffId);
      if (raw == null) {
        // Diagnostic correlator (REQ-OBS-004): the key the gateway staged was not here. Log the
        // same
        // non-reversible hashes of the subject and handoff id the gateway's stage logs (never the
        // raw
        // subject — pseudonymous PII — or the secret id), so a stage/consume pair can be lined up:
        // a
        // MATCHING sub hash with an absent key means an expired or already-consumed handoff (the
        // 5→30m TTL fix), while a DIFFERENT sub hash means a subject mismatch between the
        // device-grant
        // token and this browser session. This is what disambiguates the "Import-Link abgelaufen
        // oder
        // ungültig" notice.
        log.info(
            "Ingest handoff not found (sub=u-{}, hid=h-{}, kind={}) — expired, already consumed, or"
                + " subject mismatch",
            mask(sub),
            mask(handoffId),
            expectedKind);
        return Optional.empty();
      }
      StagedHandoff staged = MAPPER.readValue(raw, StagedHandoff.class);
      if (staged.kind() != expectedKind || staged.draftJson() == null) {
        return Optional.empty();
      }
      return Optional.of(MAPPER.readValue(staged.draftJson(), draftType));
    } catch (DataAccessException | JacksonException e) {
      log.warn("Ingest handoff read failed ({}): {}", expectedKind, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Produces a short, non-reversible correlation token for a subject or handoff id so a consume
   * miss can be logged without leaking the raw subject (pseudonymous PII) or the raw id (a
   * bearer-grade secret) — REQ-OBS-004. Uses {@link String#hashCode()}, whose algorithm is
   * JVM-independent, so the gateway's stage-side masking of the same input yields the same token
   * and the two log lines line up.
   *
   * @param value the subject or handoff id to mask; {@code null} yields the literal {@code "none"}
   * @return the lower-case hex of the value's hash, or {@code "none"} for a {@code null} input
   */
  private static @NotNull String mask(String value) {
    return value == null ? "none" : Integer.toHexString(value.hashCode());
  }
}
