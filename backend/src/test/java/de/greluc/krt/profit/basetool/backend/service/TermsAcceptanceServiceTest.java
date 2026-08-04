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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.TermsAcceptance;
import de.greluc.krt.profit.basetool.backend.repository.TermsAcceptanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-level behaviour of {@link TermsAcceptanceService} (REQ-SEC-028): the sort translation, the
 * deliberately one-sided cache, and the idempotence of recording consent.
 *
 * <p>The sort tests are the ones that matter operationally. {@code AdminTermsController} lets a
 * caller sort by {@code acceptedAt}, but that column lives on the outer-joined acceptance row, not
 * on the query root — passing it through unchanged makes Hibernate reject the entire query at
 * runtime. {@code TermsAcceptanceQueryDataTest} pins the repository half of that contract against
 * real Postgres; these pin that the service actually performs the translation, which is what stands
 * between the admin's sort click and a 500.
 */
@ExtendWith(MockitoExtension.class)
class TermsAcceptanceServiceTest {

  private static final String VERSION = "version-under-test";

  @Mock private TermsAcceptanceRepository termsAcceptanceRepository;
  @Mock private TermsVersionProvider termsVersionProvider;

  private MeterRegistry meterRegistry;
  private TermsAcceptanceService service;
  private UUID userId;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service =
        new TermsAcceptanceService(termsAcceptanceRepository, termsVersionProvider, meterRegistry);
    userId = UUID.randomUUID();
  }

  /** The translation the admin overview depends on: a bare property becomes an alias path. */
  @Test
  void translatesTheJoinedSortPropertyIntoItsAliasPath() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.findAcceptanceStatus(any(), any(), any()))
        .thenReturn(new PageImpl<>(java.util.List.of()));

    service.findAcceptanceStatus("ALL", PageRequest.of(0, 20, Sort.by("acceptedAt")));

    assertThat(capturedSortProperties()).containsExactly("ta.acceptedAt");
  }

  /** User attributes are aliased too, so the query never sees an unqualified property. */
  @Test
  void translatesUserSortPropertiesAsWell() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.findAcceptanceStatus(any(), any(), any()))
        .thenReturn(new PageImpl<>(java.util.List.of()));

    service.findAcceptanceStatus("ALL", PageRequest.of(0, 20, Sort.by("username")));

    assertThat(capturedSortProperties()).containsExactly("u.username");
  }

  /** An unsorted request is passed through untouched rather than given an arbitrary order. */
  @Test
  void leavesAnUnsortedRequestAlone() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.findAcceptanceStatus(any(), any(), any()))
        .thenReturn(new PageImpl<>(java.util.List.of()));

    service.findAcceptanceStatus("ALL", PageRequest.of(0, 20));

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.captor();
    verify(termsAcceptanceRepository)
        .findAcceptanceStatus(eq(VERSION), eq("ALL"), captor.capture());
    assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
  }

  /**
   * A property the controller allows but the service cannot map fails loudly. The two lists are
   * maintained by hand in different classes, so the mismatch is a realistic mistake — and a loud
   * failure names it, where passing it through would surface as an opaque Hibernate error.
   */
  @Test
  void rejectsASortPropertyItCannotMap() {
    assertThatThrownBy(
            () -> service.findAcceptanceStatus("ALL", PageRequest.of(0, 20, Sort.by("email"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("email");

    verify(termsAcceptanceRepository, never()).findAcceptanceStatus(any(), any(), any());
  }

  /** A positive answer is cached, so the second call never reaches the database. */
  @Test
  void cachesAPositiveAcceptanceAnswer() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION)).thenReturn(true);

    assertThat(service.hasAcceptedCurrentTerms(userId)).isTrue();
    assertThat(service.hasAcceptedCurrentTerms(userId)).isTrue();

    verify(termsAcceptanceRepository, times(1)).existsByUserIdAndTermsVersion(userId, VERSION);
  }

  /**
   * A negative answer is deliberately NOT cached: with several instances behind the proxy, a user
   * who accepts on one instance must not stay blocked on another until an entry expires.
   */
  @Test
  void doesNotCacheANegativeAcceptanceAnswer() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false);

    assertThat(service.hasAcceptedCurrentTerms(userId)).isFalse();
    assertThat(service.hasAcceptedCurrentTerms(userId)).isFalse();

    verify(termsAcceptanceRepository, times(2)).existsByUserIdAndTermsVersion(userId, VERSION);
  }

  /** Recording consent writes one row and counts one acceptance. */
  @Test
  void recordsConsentAndCountsIt() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false);

    assertThat(service.acceptCurrentTerms(userId)).isTrue();

    ArgumentCaptor<TermsAcceptance> captor = ArgumentCaptor.captor();
    verify(termsAcceptanceRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    assertThat(captor.getValue().getTermsVersion()).isEqualTo(VERSION);
    assertThat(captor.getValue().getAcceptedAt()).isNotNull();
    assertThat(meterRegistry.counter("basetool.terms.acceptances").count()).isEqualTo(1.0);
  }

  /** Accepting twice in one process writes once — no second history row, no second count. */
  @Test
  void repeatedConsentIsANoOp() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false);

    assertThat(service.acceptCurrentTerms(userId)).isTrue();
    assertThat(service.acceptCurrentTerms(userId)).isFalse();

    verify(termsAcceptanceRepository, times(1)).save(any());
    assertThat(meterRegistry.counter("basetool.terms.acceptances").count()).isEqualTo(1.0);
  }

  /**
   * A race with another instance is absorbed, and the verdict is <strong>re-read</strong> rather
   * than assumed.
   *
   * <p>The caller still gets a clean "already accepted" instead of a 500. What changed is that the
   * catch no longer caches: {@code DataIntegrityViolationException} does not say <em>which</em>
   * constraint fired, and from inside an aborted transaction that cannot be established. A unique
   * violation does imply a committed row, but the foreign key to {@code app_user} raises the same
   * exception type with nothing committed at all — and caching that positive would let a subject
   * with no local row through the consent gate for the process lifetime. One extra {@code exists}
   * query buys the distinction.
   */
  @Test
  void absorbsAConcurrentAcceptanceFromAnotherInstanceAndRereadsTheVerdict() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false, true);
    when(termsAcceptanceRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("uq_terms_acceptance_user_version"));

    assertThat(service.acceptCurrentTerms(userId)).isFalse();
    // The winner's row satisfies this user, so the gate lets them through — established by asking
    // the database, which is why the second stubbed answer is the one that decides.
    assertThat(service.hasAcceptedCurrentTerms(userId)).isTrue();
    verify(termsAcceptanceRepository, times(2)).existsByUserIdAndTermsVersion(userId, VERSION);
    assertThat(meterRegistry.counter("basetool.terms.acceptances").count()).isEqualTo(0.0);
  }

  /**
   * Consent recorded inside a transaction is not cached until that transaction commits.
   *
   * <p>The defect this closes: {@code TermsAcceptance} carries an assigned {@code @Id} and no
   * {@code @Version}, so {@code save()} issues no SQL — the insert, and any constraint violation it
   * trips, surfaces at <strong>commit</strong>, after the method has returned and after the cache
   * was written. A caller whose insert failed there was remembered as consenting for the process
   * lifetime with no row to show for it, and REQ-SEC-028's gate then waved them through until the
   * next deploy.
   *
   * <p>Drives the real {@code TransactionSynchronizationManager} and deliberately never fires
   * {@code afterCommit}, which is precisely the failed-commit case.
   */
  @Test
  void doesNotCacheConsentUntilTheTransactionCommits() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(service.acceptCurrentTerms(userId)).isTrue();

      // No commit happened, so nothing may be remembered: the next probe must ask the database.
      assertThat(service.hasAcceptedCurrentTerms(userId)).isFalse();
      verify(termsAcceptanceRepository, times(2)).existsByUserIdAndTermsVersion(userId, VERSION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** Once the transaction commits, the verdict is remembered and the query stops repeating. */
  @Test
  void cachesConsentOnceTheTransactionCommits() {
    when(termsVersionProvider.getCurrentVersion()).thenReturn(VERSION);
    when(termsAcceptanceRepository.existsByUserIdAndTermsVersion(userId, VERSION))
        .thenReturn(false);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(service.acceptCurrentTerms(userId)).isTrue();
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);

      assertThat(service.hasAcceptedCurrentTerms(userId)).isTrue();
      // Exactly one: accept()'s own probe. The verdict came from memory, which is the point.
      verify(termsAcceptanceRepository, times(1)).existsByUserIdAndTermsVersion(userId, VERSION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * Reads the sort properties the service handed to the repository.
   *
   * @return the captured sort property names, in order
   */
  private java.util.List<String> capturedSortProperties() {
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.captor();
    verify(termsAcceptanceRepository)
        .findAcceptanceStatus(eq(VERSION), eq("ALL"), captor.capture());
    return captor.getValue().getSort().stream().map(Sort.Order::getProperty).toList();
  }
}
