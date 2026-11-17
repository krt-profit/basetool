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
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * The hard byte budget of the exchange data the gateway writes to Redis: per client and member, per
 * client and in total (REQ-XCH-023, ADR-0221). Every stored value registers {@code <key>|<charge>}
 * in a sorted set per scope, scored by its expiry, and each scope keeps its running total beside
 * it. Each step prunes expired entries, checks all three limits and records the entry in one
 * optimistic transaction (ADR-0389), so parallel writes cannot overshoot and no step reads a whole
 * set. The total's use and each registry client's use are published as gauges, and a refusal's wait
 * is read from the expiries the sets are scored by.
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
  private static final long UNCHECKED = -1L;

  /** The position of the client's scope among a step's three scopes. */
  private static final int CLIENT = 1;

  /** The position of the whole exchange's scope among a step's three scopes. */
  private static final int TOTAL = 2;

  /** The wait of a charge that overflows a scope holding no entry, which no expiry frees. */
  private static final long NO_WAIT = -1L;

  /** The most entries per scope the wait reads. */
  static final int WAIT_WALK = 1000;

  /** The shortest {@code Retry-After} of a budget refusal, in seconds. */
  static final long MIN_RETRY_AFTER_SECONDS = 1;

  /** The {@code Retry-After} of a budget refusal whose wait cannot be read. */
  static final long FALLBACK_RETRY_AFTER_SECONDS = 60;

  /** The step name of a budget update on the conflict counter. */
  static final String STEP = "budget";

  private final StringRedisTemplate redisTemplate;
  private final OptimisticTransactions transactions;
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
   * @param transactions runs the budget's read-check-write steps
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   */
  @Autowired
  public ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull OptimisticTransactions transactions,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry) {
    this(redisTemplate, transactions, properties, meterRegistry, Clock.systemUTC());
  }

  /**
   * Creates the budget on the given clock.
   *
   * @param redisTemplate the Redis access
   * @param transactions runs the budget's read-check-write steps
   * @param properties the budgets
   * @param meterRegistry receives the usage gauge
   * @param clock the time source
   */
  ExchangeBudget(
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull OptimisticTransactions transactions,
      @NotNull ExchangeStoreProperties properties,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    this.redisTemplate = redisTemplate;
    this.transactions = transactions;
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
    } catch (ExchangeUnavailableException _) {
      log.warn("An exchange budget entry could not be released");
    }
  }

  /**
   * Runs one budget step for a client and member as an optimistic transaction and keeps the gauges
   * current.
   *
   * @param clientId the client
   * @param member the member
   * @param release the entry to remove, or empty
   * @param add the entry to add, or empty
   * @param ttl the added entry's lifetime
   * @param checked whether the limits are checked
   * @return {@code true} when the step was applied
   * @throws ExchangeUnavailableException if Redis cannot be reached, or the step met a changed key
   *     on every attempt
   */
  private boolean run(
      @NotNull String clientId,
      @NotNull String member,
      @NotNull String release,
      @NotNull String add,
      @NotNull Duration ttl,
      boolean checked) {
    long now = clock.millis();
    long expiry = now + ttl.toMillis();
    long ttlMillis = Math.max(ttl.toMillis(), SET_TTL.toMillis());
    List<String> sets = scopes(clientId, member);
    long[] limits = checked ? limits() : new long[] {UNCHECKED, UNCHECKED, UNCHECKED};
    List<String> keys =
        Stream.concat(sets.stream(), sets.stream().map(ExchangeBudget::sum)).toList();
    Outcome outcome;
    try {
      outcome =
          transactions.run(
              STEP, keys, () -> plan(sets, limits, now, release, add, expiry, ttlMillis));
    } catch (RuntimeException e) {
      log.warn("Exchange budget update failed: {}", e.getClass().getSimpleName());
      throw new ExchangeUnavailableException("The exchange budget cannot be reached.", e);
    }
    clientGauge(clientId).set(outcome.client());
    totalUsed.set(outcome.total());
    return outcome.fits();
  }

  /**
   * Reads the three scopes of a step and plans its writes: it prunes their expired entries and,
   * when the added entry fits all three limits, releases and adds. A step that leaves every scope
   * as it found it plans no write, so it never aborts a concurrent step.
   *
   * @param sets the member's, the client's and the whole exchange's scope
   * @param limits their limits, {@link #UNCHECKED} for none
   * @param now the current time in epoch milliseconds
   * @param release the entry to remove, or empty
   * @param add the entry to add, or empty
   * @param expiry the added entry's expiry in epoch milliseconds
   * @param ttlMillis the least lifetime the sets and totals keep
   * @return whether the entry fitted and what the client's and the total scope count afterwards
   */
  private OptimisticTransactions.@NotNull Plan<Outcome> plan(
      @NotNull List<String> sets,
      long @NotNull [] limits,
      long now,
      @NotNull String release,
      @NotNull String add,
      long expiry,
      long ttlMillis) {
    List<BudgetScope> scopes =
        sets.stream()
            .map(set ->
                BudgetScope.read(redisTemplate, set, sum(set), now, PRUNE_BATCH, release, add))
            .toList();
    boolean fits =
        add.isEmpty()
            || IntStream.range(0, scopes.size())
                .allMatch(i -> scopes.get(i).admits(release, add, limits[i]));
    List<BudgetScope.Change> changes =
        scopes.stream().map(scope -> scope.change(fits, release, add, expiry)).toList();
    Outcome outcome = new Outcome(fits, changes.get(CLIENT).used(), changes.get(TOTAL).used());
    if (changes.stream().noneMatch(BudgetScope.Change::alters)) {
      return OptimisticTransactions.Plan.read(outcome);
    }
    return OptimisticTransactions.Plan.write(
        outcome,
        () -> {
          for (int i = 0; i < scopes.size(); i++) {
            scopes.get(i).write(redisTemplate, changes.get(i), release, add, expiry, ttlMillis);
          }
        });
  }

  /**
   * Returns how many seconds a value of the given size has to wait before it fits every budget of
   * its client and member, from the expiries of the entries each overflowing scope holds: never
   * shorter than the time until the entries now held free enough, at least {@value
   * #MIN_RETRY_AFTER_SECONDS}, and {@value #FALLBACK_RETRY_AFTER_SECONDS} when Redis cannot be read
   * or an overflowing scope holds no entry.
   *
   * @param clientId the client
   * @param member the member
   * @param bytes the value's size, as passed to {@link #reserve}
   * @return the seconds for {@code Retry-After}
   */
  public long retryAfterSeconds(@NotNull String clientId, @NotNull String member, long bytes) {
    long waitMillis;
    try {
      waitMillis = waitMillis(scopes(clientId, member), limits(), clock.millis(), charge(bytes));
    } catch (RuntimeException e) {
      log.warn("Exchange budget wait could not be read: {}", e.getClass().getSimpleName());
      return FALLBACK_RETRY_AFTER_SECONDS;
    }
    if (waitMillis == NO_WAIT) {
      return FALLBACK_RETRY_AFTER_SECONDS;
    }
    return Math.max(MIN_RETRY_AFTER_SECONDS, Math.ceilDiv(waitMillis, 1000L));
  }

  /**
   * Returns how long a charge waits before it fits every scope, read without a transaction since
   * the answer is only a hint: for each scope it would overflow, the time until enough of the
   * scope's entries expire.
   *
   * @param sets the member's, the client's and the whole exchange's scope
   * @param limits their limits
   * @param now the current time in epoch milliseconds
   * @param charge the bytes the entry is charged with
   * @return the longest wait in milliseconds, zero when the charge fits now, or {@link #NO_WAIT}
   *     when an overflowing scope holds no entry
   */
  private long waitMillis(
      @NotNull List<String> sets, long @NotNull [] limits, long now, long charge) {
    long wait = 0L;
    for (int i = 0; i < sets.size(); i++) {
      Long used = StoredNumbers.parse(redisTemplate.opsForValue().get(sum(sets.get(i))));
      long over = (used == null ? 0L : used) + charge - limits[i];
      if (over > 0) {
        OptionalLong freed = expiryFreeing(sets.get(i), over);
        if (freed.isEmpty()) {
          return NO_WAIT;
        }
        wait = Math.max(wait, freed.getAsLong() - now);
      }
    }
    return wait;
  }

  /**
   * Returns when enough of a scope's entries have expired to free the given bytes, walking at most
   * {@link #WAIT_WALK} entries in expiry order; when they do not free enough, the scope's latest
   * expiry, the moment the scope is empty.
   *
   * @param set the scope's sorted set
   * @param bytes the bytes to free
   * @return the expiry in epoch milliseconds, or empty when the scope holds no entry
   */
  private @NotNull OptionalLong expiryFreeing(@NotNull String set, long bytes) {
    ZSetOperations<String, String> entries = redisTemplate.opsForZSet();
    Set<TypedTuple<String>> walk = entries.rangeWithScores(set, 0, WAIT_WALK - 1L);
    long freed = 0L;
    for (TypedTuple<String> entry : walk == null ? Set.<TypedTuple<String>>of() : walk) {
      String value = entry.getValue();
      freed += value == null ? 0L : BudgetScope.chargeOf(value);
      if (freed >= bytes) {
        return expiryOf(entry);
      }
    }
    Set<TypedTuple<String>> last = entries.rangeWithScores(set, -1, -1);
    return last == null || last.isEmpty() ? OptionalLong.empty() : expiryOf(last.iterator().next());
  }

  /**
   * Returns an entry's expiry, its score.
   *
   * @param entry the entry with its score
   * @return the expiry in epoch milliseconds, or empty when Redis returned no score
   */
  private static @NotNull OptionalLong expiryOf(@NotNull TypedTuple<String> entry) {
    Double score = entry.getScore();
    return score == null ? OptionalLong.empty() : OptionalLong.of(score.longValue());
  }

  /**
   * Returns the three scopes of a client and member.
   *
   * @param clientId the client
   * @param member the member
   * @return the member's, the client's and the whole exchange's sorted set, in that order
   */
  private static @NotNull @Unmodifiable List<String> scopes(
      @NotNull String clientId, @NotNull String member) {
    return List.of(memberScope(clientId, member), clientScope(clientId), totalScope());
  }

  /**
   * Returns the configured limits in the order of {@link #scopes}.
   *
   * @return the member's, the client's and the total budget in bytes
   */
  private long @NotNull [] limits() {
    return new long[] {properties.memberBytes(), properties.clientBytes(), properties.totalBytes()};
  }

  /**
   * What a budget step did.
   *
   * @param fits whether the entry fitted, so the step released and added
   * @param client the bytes the client's scope counts afterwards
   * @param total the bytes the whole exchange's scope counts afterwards
   */
  private record Outcome(boolean fits, long client, long total) {}

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
