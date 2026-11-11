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

import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;

/**
 * The DPoP {@code jti} replay cache of one path scope, partitioned by member (REQ-XCH-006,
 * REQ-XCH-023): every member may hold at most {@code maxPerMember} live proofs, so one member
 * cannot fill the store and lock the others out, and the whole store holds at most {@code
 * maxTotal}. A proof is kept until its {@code iat} plus the clock skew, as Spring's own cache does;
 * a member's expired proofs are dropped before its cap is checked, and a refused proof is counted
 * on {@code basetool_ingest_dpop_replay_refused_total} and takes no room.
 */
public final class DpopProofReplayStore {

  /** The prefix of the partition of a token without a subject: the proof's key. */
  static final String KEY_PARTITION_PREFIX = "key:";

  /** Orders a member's live proofs by when they expire, the earliest first. */
  private static final Comparator<Held> BY_EXPIRY =
      Comparator.comparing((Held held) -> held.entry().expiresAt()).thenComparing(Held::jtiHash);

  /** Live proofs by the SHA-256 of their {@code jti}. */
  private final ConcurrentMap<String, Entry> proofs = new ConcurrentHashMap<>();

  /** Each member partition's live proofs, earliest expiry first, guarded by the store monitor. */
  private final Map<String, NavigableSet<Held>> perMember = new HashMap<>();

  /** Live proofs ordered by expiry, guarded by the store monitor. */
  private final NavigableSet<Held> byExpiry = new TreeSet<>(BY_EXPIRY);

  /** The most live proofs one member may hold. */
  private final int maxPerMember;

  /** The most live proofs the store holds. */
  private final int maxTotal;

  /** The time source. */
  private final @NotNull Clock clock;

  /** Counts a replayed proof. */
  private final @NotNull Counter replayed;

  /** Counts a proof refused because its member holds too many. */
  private final @NotNull Counter memberCap;

  /** Counts a proof refused because the store is full. */
  private final @NotNull Counter full;

  /**
   * Creates the store of one path scope.
   *
   * @param pathScope the {@code path_scope} label, {@code exchange} or {@code other}
   * @param maxPerMember the most live proofs one member may hold
   * @param maxTotal the most live proofs the store holds
   * @param meterRegistry where the refusals are counted, registered at zero
   * @param clock the time source
   */
  public DpopProofReplayStore(
      @NotNull String pathScope,
      int maxPerMember,
      int maxTotal,
      @NotNull MeterRegistry meterRegistry,
      @NotNull Clock clock) {
    if (maxPerMember < 1 || maxTotal < maxPerMember) {
      throw new IllegalArgumentException("need 1 <= maxPerMember <= maxTotal");
    }
    this.maxPerMember = maxPerMember;
    this.maxTotal = maxTotal;
    this.clock = clock;
    this.replayed = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_REPLAYED);
    this.memberCap = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_MEMBER_CAP);
    this.full = counter(meterRegistry, pathScope, MetricNames.DPOP_REPLAY_FULL);
  }

  /**
   * The member's view of the store, for Spring's {@link DPoPProofReplayValidator}.
   *
   * @param member the access token's subject; a token without one is partitioned by its proof key
   * @return a cache whose {@code putIfAbsent} claims a proof for that member and remembers a
   *     refusal
   */
  public @NotNull MemberView forMember(@Nullable String member) {
    return new MemberView(member == null || member.isBlank() ? "" : member);
  }

  /**
   * Returns how many live proofs the store holds.
   *
   * @return the number of live proofs, expired ones not yet swept included
   */
  int size() {
    return proofs.size();
  }

  /**
   * Claims a proof for a member.
   *
   * @param jtiHash the SHA-256 of the proof's {@code jti}
   * @param expiresAt when the proof may be forgotten
   * @param partition the member partition
   * @return {@link Claim#STORED} when the proof is new and was stored; otherwise why it was
   *     refused, and for a member at its cap when it may try again
   */
  @NotNull
  synchronized Claim claim(
      @NotNull String jtiHash, @NotNull Instant expiresAt, @NotNull String partition) {
    cleanup();
    if (proofs.containsKey(jtiHash)) {
      replayed.increment();
      return Claim.REPLAYED;
    }
    if (proofs.size() >= maxTotal) {
      full.increment();
      Instant now = clock.instant();
      return Claim.full(retryAfterSeconds(now, earliestExpiry(now)));
    }
    Instant now = clock.instant();
    Held held = new Held(jtiHash, new Entry(expiresAt, partition));
    NavigableSet<Held> live = perMember.computeIfAbsent(partition, _ -> new TreeSet<>(BY_EXPIRY));
    if (live.size() >= maxPerMember) {
      memberCap.increment();
      return Claim.memberCap(retryAfterSeconds(now, live.first().entry().expiresAt()));
    }
    if (proofs.putIfAbsent(jtiHash, held.entry()) != null) {
      replayed.increment();
      return Claim.REPLAYED;
    }
    live.add(held);
    byExpiry.add(held);
    return Claim.STORED;
  }

  /**
   * Returns the whole seconds until a proof expiring at {@code oldest} no longer counts, at least
   * one: a proof counts until a moment strictly after its expiry.
   *
   * @param now the current time
   * @param oldest when the member's earliest live proof expires
   * @return the seconds after which that proof no longer counts
   */
  static long retryAfterSeconds(@NotNull Instant now, @NotNull Instant oldest) {
    return Math.max(0L, Duration.between(now, oldest).toMillis()) / 1000L + 1L;
  }

  /**
   * Returns when the store's earliest live proof expires.
   *
   * @param now the current time, returned when the store holds no live proof
   * @return the earliest expiry of a proof that still counts, or {@code now}
   */
  private @NotNull Instant earliestExpiry(@NotNull Instant now) {
    return byExpiry.isEmpty() ? now : byExpiry.first().entry().expiresAt();
  }

  /** Forgets the expired prefix of the expiry index. */
  synchronized void cleanup() {
    Instant now = clock.instant();
    while (!byExpiry.isEmpty() && now.isAfter(byExpiry.first().entry().expiresAt())) {
      Held expired = byExpiry.pollFirst();
      proofs.remove(expired.jtiHash(), expired.entry());
      release(expired);
    }
  }

  /**
   * Takes a proof off its member's queue, dropping the member when none is left.
   *
   * @param held the proof
   */
  private void release(@NotNull Held held) {
    perMember.computeIfPresent(
        held.entry().partition(),
        (_, queue) -> {
          queue.remove(held);
          return queue.isEmpty() ? null : queue;
        });
  }

  /**
   * Registers one refusal reason at zero.
   *
   * @param registry the meter registry
   * @param pathScope the path scope label
   * @param reason the refusal reason
   * @return the counter
   */
  private static @NotNull Counter counter(
      @NotNull MeterRegistry registry, @NotNull String pathScope, @NotNull String reason) {
    return registry.counter(
        MetricNames.DPOP_REPLAY_REFUSED,
        MetricNames.TAG_PATH_SCOPE,
        pathScope,
        MetricNames.TAG_REASON,
        reason);
  }

  /** Why a claim ended as it did. */
  enum Outcome {
    /** The proof was new and is now held. */
    STORED,
    /** The proof's {@code jti} is already held. */
    REPLAYED,
    /** The member already holds its cap of live proofs. */
    MEMBER_CAP,
    /** The store holds its total cap. */
    FULL
  }

  /**
   * The outcome of a claim.
   *
   * @param outcome why the claim ended as it did
   * @param retryAfterSeconds for {@link Outcome#MEMBER_CAP}, the whole seconds until the member's
   *     earliest live proof no longer counts, and for {@link Outcome#FULL} until the store's
   *     earliest does, at least one; otherwise zero
   */
  record Claim(@NotNull Outcome outcome, long retryAfterSeconds) {

    /** A stored proof. */
    static final Claim STORED = new Claim(Outcome.STORED, 0L);

    /** A replayed proof. */
    static final Claim REPLAYED = new Claim(Outcome.REPLAYED, 0L);

    /**
     * A proof refused because the store is full.
     *
     * @param retryAfterSeconds the whole seconds until the store's earliest proof no longer counts
     * @return the claim
     */
    static @NotNull Claim full(long retryAfterSeconds) {
      return new Claim(Outcome.FULL, retryAfterSeconds);
    }

    /**
     * A proof refused because its member is at the cap.
     *
     * @param retryAfterSeconds the whole seconds until the member's earliest proof no longer counts
     * @return the claim
     */
    static @NotNull Claim memberCap(long retryAfterSeconds) {
      return new Claim(Outcome.MEMBER_CAP, retryAfterSeconds);
    }
  }

  /**
   * A stored proof.
   *
   * @param expiresAt when the proof may be forgotten
   * @param partition the member partition that holds it
   */
  private record Entry(@NotNull Instant expiresAt, @NotNull String partition) {}

  /**
   * A proof in its member's queue.
   *
   * @param jtiHash the SHA-256 of its {@code jti}
   * @param entry what the store holds for it
   */
  private record Held(@NotNull String jtiHash, @NotNull Entry entry) {}

  /**
   * One member's view of the store; the replay validator only calls {@code putIfAbsent}. A view
   * serves one proof and remembers why that proof was refused.
   */
  public final class MemberView implements Cache {

    /** The subject this view claims proofs for, or empty to partition by proof key. */
    private final @NotNull String member;

    /** The last refused claim of this view, or {@code null}. */
    private volatile @Nullable Claim refused;

    /**
     * Creates the view.
     *
     * @param member the subject, or empty to partition by proof key
     */
    private MemberView(@NotNull String member) {
      this.member = member;
    }

    /**
     * Returns when this view's member may send a proof again, if its last proof was refused for the
     * member cap.
     *
     * @return the whole seconds until the member's earliest live proof no longer counts, or {@code
     *     null} when the last proof was not refused for the cap
     */
    public @Nullable Long proofLimitRetryAfter() {
      return retryAfter(Outcome.MEMBER_CAP);
    }

    /**
     * Returns when a proof may be sent again, if this view's last proof was refused because the
     * whole store was full.
     *
     * @return the whole seconds until the store's earliest live proof no longer counts, or {@code
     *     null} when the last proof was not refused for a full store
     */
    public @Nullable Long storeFullRetryAfter() {
      return retryAfter(Outcome.FULL);
    }

    /**
     * Returns the seconds of the last refusal when it had the given outcome.
     *
     * @param outcome the refusal outcome asked about
     * @return the refusal's seconds, or {@code null} when the last proof was not refused so
     */
    private @Nullable Long retryAfter(@NotNull Outcome outcome) {
      Claim last = refused;
      return last != null && last.outcome() == outcome ? last.retryAfterSeconds() : null;
    }

    @Override
    public @NotNull String getName() {
      return "dpop-proof-replay";
    }

    @Override
    public @NotNull Object getNativeCache() {
      return proofs;
    }

    @Override
    public @Nullable ValueWrapper get(@NotNull Object key) {
      Entry entry = proofs.get(key);
      return entry == null ? null : new SimpleValueWrapper(entry);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void put(Object key, Object value) {
      putIfAbsent(key, value);
    }

    /**
     * Claims the proof for this view's member.
     *
     * @param key the SHA-256 of the proof's {@code jti}
     * @param value Spring's {@link DPoPProofReplayValidator.CacheValue} of the proof
     * @return {@code null} when the proof was stored, a wrapper of the value when it was refused
     */
    @Override
    public @Nullable ValueWrapper putIfAbsent(Object key, Object value) {
      if (!(key instanceof String jtiHash)
          || !(value instanceof DPoPProofReplayValidator.CacheValue proof)) {
        return new SimpleValueWrapper(value);
      }
      String partition =
          member.isEmpty() ? KEY_PARTITION_PREFIX + proof.getJwkThumbprint() : member;
      Claim claim = claim(jtiHash, proof.getExpiresAt(), partition);
      if (claim.outcome() == Outcome.STORED) {
        return null;
      }
      refused = claim;
      return new SimpleValueWrapper(value);
    }

    @Override
    public void evict(Object key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException();
    }
  }
}
