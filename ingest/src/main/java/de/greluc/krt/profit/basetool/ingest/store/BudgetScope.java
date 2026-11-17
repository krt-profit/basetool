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

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;

/**
 * One scope of the exchange's byte budget as a budget step reads it under {@code WATCH}
 * (REQ-XCH-023, ADR-0389): the bytes its running total counts once the expired entries the step
 * prunes are gone, and what the scope holds of the entries the step releases and adds.
 *
 * <p>A scope is a sorted set of {@code <key>|<charge>} entries scored by their expiry in epoch
 * milliseconds, with its running total in a string beside it.
 *
 * @param set the scope's sorted set
 * @param sum the key of the scope's running total
 * @param used the bytes the scope counts after pruning, never negative
 * @param expired the expired entries the step prunes
 * @param remaining how many entries the set holds after pruning
 * @param releaseHeld whether the set still holds the entry to release after pruning
 * @param addExpiry the expiry the set holds the entry to add with after pruning, or {@code null}
 *     when it does not hold it
 */
record BudgetScope(
    @NotNull String set,
    @NotNull String sum,
    long used,
    @NotNull @Unmodifiable Set<String> expired,
    long remaining,
    boolean releaseHeld,
    @Nullable Double addExpiry) {

  /** The lowest score an entry can have; scores are expiries in epoch milliseconds. */
  private static final double NO_EXPIRY = 0.0d;

  /**
   * The {@code PEXPIRE} conditions that keep a key at least a lifetime: {@code NX} gives one to a
   * key without, {@code GT} raises a shorter one, so an expiry never falls.
   */
  private static final List<ExpirationOptions.Condition> RAISE_ONLY =
      List.of(ExpirationOptions.Condition.NX, ExpirationOptions.Condition.GT);

  /**
   * Reads a scope.
   *
   * @param template the Redis access, inside the step's watch
   * @param set the scope's sorted set
   * @param sum the key of its running total
   * @param now the current time in epoch milliseconds; entries expiring by then are pruned
   * @param pruneBatch the most expired entries pruned at once
   * @param release the entry the step releases, or empty
   * @param add the entry the step adds, or empty
   * @return the scope as the step finds it
   */
  static @NotNull BudgetScope read(
      @NotNull StringRedisTemplate template,
      @NotNull String set,
      @NotNull String sum,
      long now,
      int pruneBatch,
      @NotNull String release,
      @NotNull String add) {
    ZSetOperations<String, String> entries = template.opsForZSet();
    Long stored = StoredNumbers.parse(template.opsForValue().get(sum));
    long total = stored != null ? stored : charges(entries.range(set, 0, -1));
    Set<String> found = entries.rangeByScore(set, NO_EXPIRY, now, 0, pruneBatch);
    Set<String> expired = found == null ? Set.of() : Set.copyOf(found);
    total -= charges(expired);
    Long size = entries.zCard(set);
    long remaining = (size == null ? 0L : size) - expired.size();
    boolean releaseHeld =
        !release.isEmpty() && !expired.contains(release) && entries.score(set, release) != null;
    Double addExpiry = add.isEmpty() || expired.contains(add) ? null : entries.score(set, add);
    return new BudgetScope(
        set,
        sum,
        remaining == 0 || total < 0 ? 0L : total,
        expired,
        remaining,
        releaseHeld,
        addExpiry);
  }

  /**
   * Returns whether the scope stays within its limit when the step releases and adds.
   *
   * @param release the entry the step releases, or empty
   * @param add the entry the step adds
   * @param limit the scope's limit in bytes; a negative limit is not checked
   * @return {@code true} when the entry fits
   */
  boolean admits(@NotNull String release, @NotNull String add, long limit) {
    long freed = releaseHeld ? chargeOf(release) : 0L;
    return limit < 0 || used - freed + chargeOf(add) <= limit;
  }

  /**
   * Returns what the step leaves of this scope.
   *
   * @param apply whether the step releases and adds, or only prunes
   * @param release the entry the step releases, or empty
   * @param add the entry the step adds, or empty
   * @param expiry the added entry's expiry in epoch milliseconds
   * @return the scope after the step
   */
  @NotNull
  Change change(boolean apply, @NotNull String release, @NotNull String add, long expiry) {
    long bytes = used;
    long held = remaining;
    boolean removesRelease = apply && releaseHeld;
    if (removesRelease) {
      bytes = Math.max(0L, bytes - chargeOf(release));
      held--;
    }
    boolean adds = apply && !add.isEmpty();
    boolean newEntry = adds && (addExpiry == null || (removesRelease && release.equals(add)));
    if (newEntry) {
      bytes += chargeOf(add);
      held++;
    }
    boolean raises = adds && !newEntry && addExpiry != null && addExpiry < expiry;
    return new Change(held == 0 ? 0L : bytes, held == 0, removesRelease, newEntry || raises);
  }

  /**
   * Queues the step's writes to this scope: the pruning, the release, the addition, the running
   * total and the expiries, which only ever rise.
   *
   * @param template the Redis access, inside the step's transaction
   * @param change what the step leaves of the scope
   * @param release the entry the step releases, or empty
   * @param add the entry the step adds, or empty
   * @param expiry the added entry's expiry in epoch milliseconds
   * @param ttlMillis the least lifetime the set and its total keep
   */
  void write(
      @NotNull StringRedisTemplate template,
      @NotNull Change change,
      @NotNull String release,
      @NotNull String add,
      long expiry,
      long ttlMillis) {
    ZSetOperations<String, String> entries = template.opsForZSet();
    if (!expired.isEmpty()) {
      entries.remove(set, expired.toArray());
    }
    if (change.removesRelease()) {
      entries.remove(set, release);
    }
    if (change.writesAdd()) {
      entries.add(set, add, expiry);
    }
    if (change.empty()) {
      template.delete(sum);
      return;
    }
    template.opsForValue().set(sum, Long.toString(change.used()), Expiration.keepTtl());
    keepAtLeast(template, set, ttlMillis);
    keepAtLeast(template, sum, ttlMillis);
  }

  /**
   * Gives a key the lifetime when it has none and raises a shorter one to it.
   *
   * @param template the Redis access
   * @param key the key
   * @param ttlMillis the lifetime in milliseconds
   */
  private static void keepAtLeast(
      @NotNull StringRedisTemplate template, @NotNull String key, long ttlMillis) {
    byte[] raw = key.getBytes(StandardCharsets.UTF_8);
    for (ExpirationOptions.Condition condition : RAISE_ONLY) {
      RedisCallback<Boolean> expire =
          connection -> connection.keyCommands().pExpire(raw, ttlMillis, condition);
      template.execute(expire);
    }
  }

  /**
   * Returns the bytes an entry is charged with.
   *
   * @param entry {@code <key>|<charge>}, or empty
   * @return the charge, or zero for an empty or malformed entry
   */
  static long chargeOf(@NotNull String entry) {
    Long charge = StoredNumbers.parse(entry.substring(entry.lastIndexOf('|') + 1));
    return charge == null || charge < 0 ? 0L : charge;
  }

  /**
   * Sums the charges of entries.
   *
   * @param entries the entries, or {@code null} for none
   * @return the bytes they are charged with
   */
  private static long charges(@Nullable Collection<String> entries) {
    return entries == null ? 0L : entries.stream().mapToLong(BudgetScope::chargeOf).sum();
  }

  /**
   * What a budget step leaves of a scope.
   *
   * @param used the bytes the scope counts afterwards
   * @param empty whether the scope holds no entry afterwards
   * @param removesRelease whether the step removes the released entry
   * @param writesAdd whether the step writes the added entry, new or with a later expiry
   */
  record Change(long used, boolean empty, boolean removesRelease, boolean writesAdd) {}
}
