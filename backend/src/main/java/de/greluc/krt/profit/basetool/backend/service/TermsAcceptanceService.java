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

package de.greluc.krt.profit.basetool.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.TermsAcceptance;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.backend.repository.TermsAcceptanceRepository;
import de.greluc.krt.profit.basetool.backend.support.TermsConsentCheck;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records and answers Terms-of-Use consent (REQ-SEC-028).
 *
 * <p>The read side runs on every authenticated API call and is backed by a cache that stores only
 * {@code true}; that stays correct across instances because consent to the version in force is
 * monotonic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TermsAcceptanceService implements TermsConsentCheck {

  /**
   * Upper bound on cached acceptances. Sized well above the real member count so the cache never
   * evicts in practice, while still bounding memory against a token-holder population that grows
   * unexpectedly — an evicted entry costs one extra query, never a wrong answer.
   */
  private static final int CACHE_MAX_ENTRIES = 10_000;

  /**
   * Maps each sort property the admin overview accepts onto its explicit JPQL alias path. Kept in
   * lockstep with {@code AdminTermsController.ALLOWED_SORT_FIELDS}: the controller decides what a
   * caller may ask for, this map decides what the query actually sorts on, and a property in one
   * but not the other fails loudly in {@link #withAliasedSort} rather than reaching Hibernate.
   */
  private static final Map<String, String> SORT_PROPERTY_PATHS =
      Map.of(
          "username", "u.username",
          "displayName", "u.displayName",
          "acceptedAt", "ta.acceptedAt");

  private final TermsAcceptanceRepository termsAcceptanceRepository;
  private final TermsVersionProvider termsVersionProvider;
  private final MeterRegistry meterRegistry;

  /** How long a cached positive verdict may outlive the read that produced it. */
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);

  /** Users known to have accepted the version in force; only {@code true} is ever stored. */
  private final Cache<UUID, Boolean> acceptedCache =
      Caffeine.newBuilder().maximumSize(CACHE_MAX_ENTRIES).expireAfterWrite(CACHE_TTL).build();

  /**
   * Registers the rollout gauge. Reported per version so a terms change is visible as a new series
   * climbing from zero — a flat line after a change means users are being blocked rather than
   * accepting, which is the failure this gate can cause and the log alone would not show. The label
   * is bounded by construction: one process serves exactly one version (REQ-OBS-011).
   */
  @PostConstruct
  void registerMetrics() {
    Gauge.builder(
            MetricNames.TERMS_ACCEPTED_USERS,
            () -> termsAcceptanceRepository.countByTermsVersion(currentVersion()))
        .description("Users who have accepted the Terms-of-Use version currently in force")
        .tag(MetricNames.TAG_TERMS_VERSION, currentVersion())
        .register(meterRegistry);
  }

  /**
   * Reports whether the user has accepted the wording currently in force.
   *
   * <p>Deliberately not {@code @Transactional}, so a cache hit on this hot path opens no
   * transaction.
   *
   * @param userId the user's {@code app_user.id}, i.e. the Keycloak {@code sub}
   * @return {@code true} if consent for the current version is on record
   */
  @Override
  public boolean hasAcceptedCurrentTerms(@NotNull UUID userId) {
    if (Boolean.TRUE.equals(acceptedCache.getIfPresent(userId))) {
      return true;
    }
    boolean accepted =
        termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, currentVersion());
    if (accepted) {
      acceptedCache.put(userId, Boolean.TRUE);
    }
    return accepted;
  }

  /**
   * Records the user's consent to the wording currently in force.
   *
   * <p>Idempotent: a repeat or a cross-instance race never writes a second history row.
   *
   * @param userId the accepting user's {@code app_user.id}, i.e. the Keycloak {@code sub}
   * @return {@code true} if this call wrote a new acceptance, {@code false} if consent already
   *     existed
   */
  @Transactional
  public boolean acceptCurrentTerms(@NotNull UUID userId) {
    if (hasAcceptedCurrentTerms(userId)) {
      return false;
    }
    TermsAcceptance acceptance =
        TermsAcceptance.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .termsVersion(currentVersion())
            .acceptedAt(Instant.now())
            .build();
    try {
      termsAcceptanceRepository.save(acceptance);
    } catch (DataIntegrityViolationException e) {
      log.debug("Concurrent terms acceptance for the same user and version; keeping the first");
      return false;
    }
    cacheAfterCommit(userId);
    meterRegistry.counter(MetricNames.TERMS_ACCEPTANCES).increment();
    log.info("Terms of Use accepted, version {}", currentVersion());
    return true;
  }

  /**
   * Caches the positive verdict only after the transaction that recorded it has committed, or
   * immediately when no transaction is active.
   *
   * @param userId the user whose consent was just recorded
   */
  private void cacheAfterCommit(@NotNull UUID userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      acceptedCache.put(userId, Boolean.TRUE);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            acceptedCache.put(userId, Boolean.TRUE);
          }
        });
  }

  /**
   * Lists users with their consent state for the wording currently in force — the admin overview.
   *
   * @param filter {@code ALL}, {@code ACCEPTED} or {@code PENDING}, already validated by the caller
   * @param pageable page, size and sort, with sort properties already whitelisted by the caller
   * @return one page of consent rows
   */
  @Transactional(readOnly = true)
  public Page<TermsAcceptanceStatusDto> findAcceptanceStatus(
      @NotNull String filter, @NotNull Pageable pageable) {
    return termsAcceptanceRepository.findAcceptanceStatus(
        currentVersion(), filter, withAliasedSort(pageable));
  }

  /**
   * Rewrites the sort properties into the JPQL alias paths they belong to, since {@code acceptedAt}
   * lives on the joined {@code TermsAcceptance} rather than the {@code User} root.
   *
   * @param pageable the requested page, whose sort properties the controller already whitelisted
   * @return an equivalent pageable whose sort names aliased paths
   * @throws IllegalArgumentException if a property has no mapping
   */
  private static @NotNull Pageable withAliasedSort(@NotNull Pageable pageable) {
    if (pageable.getSort().isUnsorted()) {
      return pageable;
    }
    JpaSort aliased = null;
    for (Sort.Order order : pageable.getSort()) {
      String path = SORT_PROPERTY_PATHS.get(order.getProperty());
      if (path == null) {
        throw new IllegalArgumentException("Unmapped sort property: " + order.getProperty());
      }
      JpaSort next = JpaSort.unsafe(order.getDirection(), path);
      aliased = aliased == null ? next : aliased.andUnsafe(order.getDirection(), path);
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), aliased);
  }

  /**
   * Counts login-capable users who have not accepted the wording currently in force.
   *
   * @return the number of users still owing consent
   */
  @Transactional(readOnly = true)
  public long countPendingUsers() {
    return termsAcceptanceRepository.countPendingUsers(currentVersion());
  }

  /**
   * The wording currently in force, as a content digest.
   *
   * @return the non-blank version string supplied by {@link TermsVersionProvider}
   */
  public @NotNull String currentVersion() {
    return termsVersionProvider.getCurrentVersion();
  }
}
