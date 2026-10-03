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

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Counts each member's daily exchange writes per client in Redis, under {@code
 * ingest:xch:quota:<client>:<member>:<UTC day>} (REQ-XCH-023). A counter is created with its expiry
 * in one command and lives until the end of the UTC day after its own, so it outlasts its day
 * wherever the gateway's clock stands; every write registers it in the byte budget under that same
 * expiry, which counts it once. A write the byte budget refuses gives its count back.
 */
@Slf4j
@Component
public class ExchangeQuotas {

  /** The key prefix of a daily counter. */
  static final String PREFIX = "ingest:xch:quota:";

  /** How long after the start of its day a counter lives. */
  static final Duration TTL = Duration.ofDays(2);

  /**
   * The request attribute holding the counter key a write was counted under, so a refusal that
   * gives the count back takes it from the same day's counter.
   */
  public static final String COUNTED = ExchangeQuotas.class.getName() + ".counted";

  /**
   * Takes one count back from a counter that holds at least one, keeping its expiry; the result is
   * the new count, or {@code -1} when there was nothing to take back.
   */
  private static final RedisScript<Long> REFUND_SCRIPT =
      new DefaultRedisScript<>(
          """
          local count = tonumber(redis.call('GET', KEYS[1]))
          if count == nil or count < 1 then
            return -1
          end
          redis.call('SET', KEYS[1], string.format('%d', count - 1), 'KEEPTTL')
          return count - 1
          """,
          Long.class);

  /** The bytes a counter is budgeted with: its key plus a long's decimal digits. */
  static final int VALUE_BYTES = 20;

  private final StringRedisTemplate redisTemplate;
  private final ExchangeBudget budget;
  private final Clock clock;

  /**
   * Creates the quotas on the system clock.
   *
   * @param redisTemplate the Redis access
   * @param budget the byte budget a new counter is registered in
   */
  @Autowired
  public ExchangeQuotas(
      @NotNull StringRedisTemplate redisTemplate, @NotNull ExchangeBudget budget) {
    this(redisTemplate, budget, Clock.systemUTC());
  }

  /**
   * Creates the quotas on the given clock.
   *
   * @param redisTemplate the Redis access
   * @param budget the byte budget a new counter is registered in
   * @param clock the time source
   */
  ExchangeQuotas(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeBudget budget,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.budget = budget;
    this.clock = clock;
  }

  /**
   * Counts one write and returns the counter it was counted on and the day's count so far.
   *
   * @param clientId the client
   * @param member the member
   * @return the counter's key and the count including this write
   * @throws ExchangeUnavailableException if Redis cannot count
   */
  public @NotNull Counted countWrite(@NotNull String clientId, @NotNull String member) {
    LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    String key = PREFIX + clientId + ":" + member + ":" + day;
    Duration ttl = Duration.between(clock.instant(), day.atStartOfDay(ZoneOffset.UTC).plus(TTL));
    try {
      budget.record(clientId, member, key, key.length() + (long) VALUE_BYTES, ttl);
      redisTemplate.opsForValue().setIfAbsent(key, "0", ttl);
      Long count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        throw new ExchangeUnavailableException("The write quota cannot be counted.", null);
      }
      return new Counted(key, count);
    } catch (ExchangeUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Exchange quota count failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The write quota cannot be counted.", e);
    }
  }

  /**
   * Gives back the count a write was counted with, once: the counter noted on the request as {@link
   * #COUNTED} is decremented and the note removed; a request with no note is left alone.
   *
   * @param request the refused write
   */
  public void refundCounted(@NotNull HttpServletRequest request) {
    if (request.getAttribute(COUNTED) instanceof String key) {
      request.removeAttribute(COUNTED);
      refund(key);
    }
  }

  /**
   * Gives one write back to the counter it was counted on, for a write refused before it could run;
   * best effort, a failure is logged and the count stays.
   *
   * @param key the counter's key, from {@link Counted#key()}
   */
  public void refund(@NotNull String key) {
    try {
      redisTemplate.execute(REFUND_SCRIPT, List.of(key));
    } catch (RuntimeException e) {
      log.warn("Exchange quota refund failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Returns the seconds until the next UTC day, when every daily counter starts over.
   *
   * @return at least one
   */
  public long secondsUntilTomorrow() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    long tomorrow = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    return Math.max(1L, tomorrow - clock.instant().getEpochSecond());
  }

  /**
   * One counted write.
   *
   * @param key the counter it was counted on
   * @param count the day's count including it
   */
  public record Counted(@NotNull String key, long count) {}
}
