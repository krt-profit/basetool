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
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The hard byte budget of the exchange data the gateway writes to Redis: per client and member, per
 * client and in total (REQ-XCH-023, ADR-0221). Every stored value registers {@code <key>|<charge>}
 * in a sorted set per scope, scored by its expiry, and each scope keeps its running total beside
 * it. One Lua script prunes expired entries, checks all three limits and records the entry
 * atomically, so parallel writes cannot overshoot and no call reads a whole set. The total's use
 * and each registry client's use are published as gauges, and a refusal's wait is read from the
 * expiries the sets are scored by.
 */
@Slf4j
@Component
public class ExchangeBudget {

  /** The key prefix of the budget sets. */
  static final String PREFIX = "ingest:xch:budget:";

  /** The key prefix of the running totals, one per budget set. */
  static final String SUM_PREFIX = "ingest:xch:budget-sum:";

  /** The key prefix of a reservation made before the value's own key is known. */
  public static final String PENDING_PREFIX = "ingest:xch:pending:";

  /** A budget set outlives every entry it counts; each call moves its expiry at least this far. */
  static final Duration SET_TTL = Duration.ofDays(3);

  /**
   * The bytes charged per entry on top of its value: its member in the three sets and the key's own
   * bookkeeping in Redis.
   */
  static final int ENTRY_OVERHEAD_BYTES = 512;

  /** The most expired entries one call prunes per set, so a call stays short. */
  static final int PRUNE_BATCH = 1000;

  /** Marks a limit that is not checked. */
  private static final String UNCHECKED = "-1";

  /**
   * The check-and-record script; its result is {@code <fits>:<member>:<client>:<total>}, whether
   * the step was applied and the bytes each scope holds after it.
   */
  private static final RedisScript<String> SCRIPT =
      new DefaultRedisScript<>(
          """
          local release = ARGV[3]
          local add = ARGV[4]
          local expiry = ARGV[5]
          local ttl = ARGV[6]
          local function size(entry)
            return tonumber(string.match(entry, '|(%d+)$')) or 0
          end
          local function empty(set)
            return #redis.call('ZRANGE', set, 0, 0) == 0
          end
          local used = {}
          local present = {}
          for i = 1, 3 do
            local set = KEYS[i]
            local raw = redis.call('GET', KEYS[i + 3])
            local total = nil
            if raw then
              total = tonumber(raw)
            end
            if total == nil then
              total = 0
              for _, entry in ipairs(redis.call('ZRANGE', set, 0, -1)) do
                total = total + size(entry)
              end
            end
            local expired = redis.call('ZRANGEBYSCORE', set, '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            if #expired > 0 then
              for _, entry in ipairs(expired) do
                total = total - size(entry)
              end
              redis.call('ZREM', set, unpack(expired))
            end
            if empty(set) or total < 0 then
              total = 0
            end
            used[i] = total
            present[i] = release ~= '' and redis.call('ZSCORE', set, release) ~= false
          end
          local fits = 1
          if add ~= '' then
            local bytes = size(add)
            for i = 1, 3 do
              local limit = tonumber(ARGV[6 + i])
              local freed = present[i] and size(release) or 0
              if limit >= 0 and used[i] - freed + bytes > limit then
                fits = 0
              end
            end
          end
          if fits == 1 then
            for i = 1, 3 do
              if present[i] then
                redis.call('ZREM', KEYS[i], release)
                used[i] = math.max(0, used[i] - size(release))
              end
              if add ~= '' and redis.call('ZADD', KEYS[i], 'GT', expiry, add) == 1 then
                used[i] = used[i] + size(add)
              end
            end
          end
          for i = 1, 3 do
            if empty(KEYS[i]) then
              redis.call('DEL', KEYS[i + 3])
              used[i] = 0
            else
              redis.call('SET', KEYS[i + 3], string.format('%d', used[i]), 'KEEPTTL')
              for _, key in ipairs({KEYS[i], KEYS[i + 3]}) do
                redis.call('PEXPIRE', key, ttl, 'NX')
                redis.call('PEXPIRE', key, ttl, 'GT')
              end
            end
          end
          return string.format('%d:%d:%d:%d', fits, used[1], used[2], used[3])
          """,
          String.class);

  /**
   * The wait script: for every scope whose limit a charge would overflow, it walks the scope's
   * entries by expiry until enough bytes expire, and returns the longest such wait in milliseconds,
   * {@code 0} when the charge fits, or {@code -1} when the entries it may read do not free enough.
   */
  private static final RedisScript<Long> WAIT_SCRIPT =
      new DefaultRedisScript<>(
          """
          local now = tonumber(ARGV[1])
          local charge = tonumber(ARGV[2])
          local walk = tonumber(ARGV[3])
          local function size(entry)
            return tonumber(string.match(entry, '|(%d+)$')) or 0
          end
          local wait = 0
          for i = 1, 3 do
            local limit = tonumber(ARGV[3 + i])
            local used = tonumber(redis.call('GET', KEYS[i + 3]) or '0') or 0
            local over = used + charge - limit
            if over > 0 then
              local freed = 0
              local at = nil
              local entries = redis.call('ZRANGE', KEYS[i], 0, walk - 1, 'WITHSCORES')
              for j = 1, #entries, 2 do
                freed = freed + size(entries[j])
                if freed >= over then
                  at = tonumber(entries[j + 1])
                  break
                end
              end
              if at == nil then
                return -1
              end
              if at - now > wait then
                wait = at - now
              end
            end
          end
          return wait
          """,
          Long.class);

  /** The most entries per scope the wait script reads. */
  static final int WAIT_WALK = 1000;

  /** The shortest {@code Retry-After} of a budget refusal, in seconds. */
  static final long MIN_RETRY_AFTER_SECONDS = 1;

  /**
   * The longest {@code Retry-After} of a budget refusal, in seconds: a full budget can take a day
   * to free, and a client asks again at least hourly.
   */
  static final long MAX_RETRY_AFTER_SECONDS = 3600;

  /** The {@code Retry-After} of a budget refusal whose wait cannot be read. */
  static final long FALLBACK_RETRY_AFTER_SECONDS = 60;

  private final StringRedisTemplate redisTemplate;
  private final ExchangeStoreProperties properties;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final AtomicLong totalUsed = new AtomicLong();

  /** The bytes each registry client's scope held at its last measurement, one gauge per client. */
  private final Map<String, AtomicLong> clientUsed = new ConcurrentHashMap<>();

  /**
   * Creates the budget on the system clock and registers its gauge.
   *
   * @param redisTemplate the Redis access
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   */
  @Autowired
  public ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry) {
    this(redisTemplate, properties, meterRegistry, Clock.systemUTC());
  }

  /**
   * Creates the budget on the given clock.
   *
   * @param redisTemplate the Redis access
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   * @param clock the time source
   */
  ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.clock = clock;
    Gauge.builder(
            MetricNames.EXCHANGE_BUDGET_USED_RATIO,
            totalUsed,
            used -> (double) used.get() / properties.totalBytes())
        .description("The share of the exchange's total Redis byte budget in use, last measured.")
        .register(meterRegistry);
  }

  /**
   * Returns what an entry of a value's size is charged: the value plus {@link
   * #ENTRY_OVERHEAD_BYTES}.
   *
   * @param bytes the value's size
   * @return the bytes counted against every scope
   */
  public static long charge(long bytes) {
    return bytes + ENTRY_OVERHEAD_BYTES;
  }

  /**
   * Records a value in every scope of its client and member only if it fits all three budgets, as
   * one atomic step.
   *
   * @param clientId the client
   * @param member the member
   * @param key the value's Redis key, or a {@link #PENDING_PREFIX} name for a reservation
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @return {@code true} when it fitted and is recorded; {@code false} when nothing was recorded
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public boolean reserve(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    return run(clientId, member, "", entry(key, bytes), ttl, true);
  }

  /**
   * Replaces a reservation with the value it was made for, atomically; when the value does not fit
   * even with the reservation freed, the reservation stays and nothing else changes.
   *
   * @param clientId the client
   * @param member the member
   * @param reservedKey the reservation's key
   * @param reservedBytes the reservation's size
   * @param key the value's Redis key
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @return {@code true} when the value is recorded in place of the reservation
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public boolean settle(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String reservedKey,
      long reservedBytes,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    return run(clientId, member, entry(reservedKey, reservedBytes), entry(key, bytes), ttl, true);
  }

  /**
   * Records a value that already exists in every scope, without a limit check.
   *
   * @param clientId the client
   * @param member the member
   * @param key the value's Redis key
   * @param bytes the value's size
   * @param ttl the value's lifetime
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  public void record(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String key,
      long bytes,
      @NotNull Duration ttl) {
    run(clientId, member, "", entry(key, bytes), ttl, false);
  }

  /**
   * Removes an entry from every scope; a failure is logged, since the entry expires on its own.
   *
   * @param clientId the client
   * @param member the member
   * @param key the entry's key
   * @param bytes the entry's size
   */
  public void release(
      @NotNull String clientId, @NotNull String member, @NotNull String key, long bytes) {
    try {
      run(clientId, member, entry(key, bytes), "", SET_TTL, false);
    } catch (ExchangeUnavailableException e) {
      log.warn("An exchange budget entry could not be released");
    }
  }

  /**
   * Runs the script for one client and member and keeps the gauges current.
   *
   * @param clientId the client
   * @param member the member
   * @param release the entry to remove, or empty
   * @param add the entry to add, or empty
   * @param ttl the added entry's lifetime
   * @param checked whether the limits are checked
   * @return {@code true} when the step was applied
   * @throws ExchangeUnavailableException if Redis cannot be reached
   */
  private boolean run(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String release,
      @NotNull String add,
      @NotNull Duration ttl,
      boolean checked) {
    long now = clock.millis();
    Duration setTtl = ttl.compareTo(SET_TTL) > 0 ? ttl : SET_TTL;
    List<String> sets = List.of(memberScope(clientId, member), clientScope(clientId), totalScope());
    List<String> keys =
        List.of(
            sets.getFirst(),
            sets.get(1),
            sets.get(2),
            sum(sets.getFirst()),
            sum(sets.get(1)),
            sum(sets.get(2)));
    String result;
    try {
      result =
          redisTemplate.execute(
              SCRIPT,
              keys,
              Long.toString(now),
              Integer.toString(PRUNE_BATCH),
              release,
              add,
              Long.toString(now + ttl.toMillis()),
              Long.toString(setTtl.toMillis()),
              checked ? Long.toString(properties.memberBytes()) : UNCHECKED,
              checked ? Long.toString(properties.clientBytes()) : UNCHECKED,
              checked ? Long.toString(properties.totalBytes()) : UNCHECKED);
    } catch (RuntimeException e) {
      log.warn("Exchange budget update failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The exchange budget cannot be reached.", e);
    }
    long[] answer = parse(result);
    clientGauge(clientId).set(answer[2]);
    totalUsed.set(answer[3]);
    return answer[0] == 1L;
  }

  /**
   * Returns how many seconds a value of the given size has to wait before it fits every budget of
   * its client and member, from the expiries of the entries each overflowing scope holds; bounded
   * to {@value #MIN_RETRY_AFTER_SECONDS}…{@value #MAX_RETRY_AFTER_SECONDS}, the upper bound also
   * when the entries it reads do not free enough, and {@value #FALLBACK_RETRY_AFTER_SECONDS} when
   * Redis cannot be read.
   *
   * @param clientId the client
   * @param member the member
   * @param bytes the value's size, as passed to {@link #reserve}
   * @return the seconds for {@code Retry-After}
   */
  public long retryAfterSeconds(@NotNull String clientId, @NotNull String member, long bytes) {
    List<String> sets = List.of(memberScope(clientId, member), clientScope(clientId), totalScope());
    Long waitMillis;
    try {
      waitMillis =
          redisTemplate.execute(
              WAIT_SCRIPT,
              List.of(
                  sets.getFirst(),
                  sets.get(1),
                  sets.get(2),
                  sum(sets.getFirst()),
                  sum(sets.get(1)),
                  sum(sets.get(2))),
              Long.toString(clock.millis()),
              Long.toString(charge(bytes)),
              Integer.toString(WAIT_WALK),
              Long.toString(properties.memberBytes()),
              Long.toString(properties.clientBytes()),
              Long.toString(properties.totalBytes()));
    } catch (RuntimeException e) {
      log.warn("Exchange budget wait could not be read: {}", e.getClass().getSimpleName());
      return FALLBACK_RETRY_AFTER_SECONDS;
    }
    if (waitMillis == null) {
      return FALLBACK_RETRY_AFTER_SECONDS;
    }
    if (waitMillis < 0) {
      return MAX_RETRY_AFTER_SECONDS;
    }
    long seconds = (waitMillis + 999L) / 1000L;
    return Math.clamp(seconds, MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS);
  }

  /**
   * Parses the check-and-record script's answer.
   *
   * @param result {@code <fits>:<member>:<client>:<total>}, or {@code null}
   * @return the four numbers in that order
   * @throws ExchangeUnavailableException if the answer is missing or malformed
   */
  static long @NotNull [] parse(@Nullable String result) {
    if (result == null) {
      throw new ExchangeUnavailableException("The exchange budget answered nothing.", null);
    }
    String[] parts = result.split(":", -1);
    if (parts.length != 4) {
      throw new ExchangeUnavailableException("The exchange budget answered malformed.", null);
    }
    long[] numbers = new long[4];
    try {
      for (int i = 0; i < 4; i++) {
        numbers[i] = Long.parseLong(parts[i]);
      }
    } catch (NumberFormatException e) {
      throw new ExchangeUnavailableException("The exchange budget answered malformed.", e);
    }
    return numbers;
  }

  /**
   * Returns the holder of a client's last measured use, registering its gauge on first use; the
   * client is always an admitted request's registry client id, so the label stays bounded by the
   * registry (REQ-OBS-011).
   *
   * @param clientId the registry client id
   * @return the holder the gauge reads
   */
  private @NotNull AtomicLong clientGauge(@NotNull String clientId) {
    return clientUsed.computeIfAbsent(
        clientId,
        id -> {
          AtomicLong used = new AtomicLong();
          Gauge.builder(
                  MetricNames.EXCHANGE_CLIENT_BUDGET_USED_RATIO,
                  used,
                  value -> (double) value.get() / properties.clientBytes())
              .description("The share of one client's Redis byte budget in use, last measured.")
              .tag(MetricNames.TAG_CLIENT_ID, id)
              .register(meterRegistry);
          return used;
        });
  }

  /**
   * Returns the set member of an entry.
   *
   * @param key the entry's key
   * @param bytes the value's size
   * @return {@code <key>|<charge>}
   */
  static @NotNull String entry(@NotNull String key, long bytes) {
    return key + "|" + charge(bytes);
  }

  /**
   * Returns the running total's key of a budget set.
   *
   * @param scope the budget set's key
   * @return the total's key
   */
  static @NotNull String sum(@NotNull String scope) {
    return SUM_PREFIX + scope.substring(PREFIX.length());
  }

  /**
   * Returns the scope of a client and member.
   *
   * @param clientId the client
   * @param member the member
   * @return the sorted set's key
   */
  static @NotNull String memberScope(@NotNull String clientId, @NotNull String member) {
    return PREFIX + "m:" + clientId + ":" + member;
  }

  /**
   * Returns the scope of a client.
   *
   * @param clientId the client
   * @return the sorted set's key
   */
  static @NotNull String clientScope(@NotNull String clientId) {
    return PREFIX + "c:" + clientId;
  }

  /**
   * Returns the scope of the whole exchange.
   *
   * @return the sorted set's key
   */
  static @NotNull String totalScope() {
    return PREFIX + "all";
  }
}
