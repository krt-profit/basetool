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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.CorrelationContext;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verifies that {@link ReactorContextPropagationConfig} actually does what its Javadoc claims: a
 * {@link ActiveSquadronContext} value set on the calling thread is visible inside a Reactor
 * pipeline that runs on a different scheduler thread.
 *
 * <p>The regression this anchors: before this config existed, the {@code
 * ActiveSquadronRelayFilter.relayActiveSquadron()} lambda observed {@code
 * ActiveSquadronContext.get() == null} on the Reactor worker thread (because classic {@link
 * ThreadLocal} values are not copied across threads), and the outbound {@code X-Active-Org-Unit-Id}
 * header was silently dropped — leaking foreign squadrons' rows into a pinned admin's Lager view.
 * Same regression applied to {@link CorrelationContext}, breaking the correlation-id join between
 * frontend and backend log lines.
 *
 * <p>The Reactor side relies on {@link
 * reactor.core.publisher.Hooks#enableAutomaticContextPropagation()} being on. That is activated in
 * {@link ReactorContextPropagationConfig#enableContextPropagation()}; we trigger it once at {@link
 * BeforeAll} time, then run a parallel-scheduler-bound assertion for each registered accessor —
 * {@link ActiveSquadronContext}, {@link CorrelationContext}, {@link ClientIpContext} and the {@link
 * LocaleContextHolder}-backed user locale. The two later-added accessors (client IP for the backend
 * per-IP rate limiter, user locale for the {@code Accept-Language} relay) would each silently drop
 * their outbound header if the registration or its null/blank branch regressed. A fifth accessor
 * carried the guest edit token for anonymous mission sign-up edits; it went with the token itself
 * (ADR-0159, V239).
 */
class ReactorContextPropagationConfigTest {

  @BeforeAll
  static void activateContextPropagation() {
    new ReactorContextPropagationConfig().enableContextPropagation();
  }

  @AfterEach
  void clearHolders() {
    ActiveSquadronContext.clear();
    CorrelationContext.clear();
    ClientIpContext.clear();
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void activeSquadronContext_isVisibleInsideMonoOnDifferentScheduler() {
    UUID pinned = UUID.fromString("7309b226-abf0-4022-857b-f2462cc8bbb5");
    ActiveSquadronContext.set(pinned);

    AtomicReference<UUID> observedOnReactorThread = new AtomicReference<>();
    AtomicReference<String> observedThreadName = new AtomicReference<>();

    Mono.fromCallable(
            () -> {
              observedOnReactorThread.set(ActiveSquadronContext.get());
              observedThreadName.set(Thread.currentThread().getName());
              return "done";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(observedOnReactorThread.get())
        .as("ActiveSquadronContext must be visible on the Reactor parallel-scheduler thread")
        .isEqualTo(pinned);
    assertThat(observedThreadName.get())
        .as(
            "sanity: the callable must have run on a Reactor parallel-N worker, not the JUnit"
                + " thread")
        .startsWith("parallel-");
  }

  @Test
  void correlationContext_isVisibleInsideMonoOnDifferentScheduler() {
    String correlationId = "test-correlation-" + UUID.randomUUID();
    CorrelationContext.set(correlationId);

    AtomicReference<String> observedOnReactorThread = new AtomicReference<>();

    Mono.fromCallable(
            () -> {
              observedOnReactorThread.set(CorrelationContext.get());
              return "done";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(observedOnReactorThread.get())
        .as("CorrelationContext must be visible on the Reactor parallel-scheduler thread")
        .isEqualTo(correlationId);
  }

  @Test
  void clientIpContext_isVisibleInsideMonoOnDifferentScheduler() {
    // TEST-NET-3 documentation range (RFC 5737) — synthetic, never a real client address.
    String clientIp = "203.0.113.7";
    ClientIpContext.set(clientIp);

    AtomicReference<String> observedOnReactorThread = new AtomicReference<>();
    AtomicReference<String> observedThreadName = new AtomicReference<>();

    Mono.fromCallable(
            () -> {
              observedOnReactorThread.set(ClientIpContext.get());
              observedThreadName.set(Thread.currentThread().getName());
              return "done";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(observedOnReactorThread.get())
        .as("ClientIpContext must be visible on the Reactor parallel-scheduler thread")
        .isEqualTo(clientIp);
    assertThat(observedThreadName.get())
        .as(
            "sanity: the callable must have run on a Reactor parallel-N worker, not the JUnit"
                + " thread")
        .startsWith("parallel-");
  }

  @Test
  void userLocaleContext_isVisibleInsideMonoOnDifferentScheduler() {
    // Pick a locale guaranteed to differ from the JVM default: on a propagation failure the worker
    // falls back to LocaleContextHolder.getLocale() == default, which must not read as a pass.
    Locale target = Locale.JAPAN.equals(Locale.getDefault()) ? Locale.CANADA_FRENCH : Locale.JAPAN;
    LocaleContextHolder.setLocale(target);

    AtomicReference<Locale> observedOnReactorThread = new AtomicReference<>();
    AtomicReference<String> observedThreadName = new AtomicReference<>();

    Mono.fromCallable(
            () -> {
              observedOnReactorThread.set(LocaleContextHolder.getLocale());
              observedThreadName.set(Thread.currentThread().getName());
              return "done";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(observedOnReactorThread.get())
        .as("the user locale must be visible on the Reactor parallel-scheduler thread")
        .isEqualTo(target);
    assertThat(observedThreadName.get())
        .as(
            "sanity: the callable must have run on a Reactor parallel-N worker, not the JUnit"
                + " thread")
        .startsWith("parallel-");
  }

  @Test
  void contextsDoNotLeakAcrossSubscriptions() {
    // Set on the JUnit thread; both holders are populated.
    ActiveSquadronContext.set(UUID.fromString("7309b226-abf0-4022-857b-f2462cc8bbb5"));
    CorrelationContext.set("first-id");

    AtomicReference<UUID> firstActive = new AtomicReference<>();
    AtomicReference<String> firstCorrelation = new AtomicReference<>();
    Mono.fromCallable(
            () -> {
              firstActive.set(ActiveSquadronContext.get());
              firstCorrelation.set(CorrelationContext.get());
              return "ok";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(firstActive.get()).isNotNull();
    assertThat(firstCorrelation.get()).isEqualTo("first-id");

    // Clear, then submit a second subscription: it must observe null on the worker, not the
    // previous subscription's snapshot. Proves the per-subscription cleanup of the SPI.
    ActiveSquadronContext.clear();
    CorrelationContext.clear();

    AtomicReference<UUID> secondActive = new AtomicReference<>();
    AtomicReference<String> secondCorrelation = new AtomicReference<>();
    Mono.fromCallable(
            () -> {
              secondActive.set(ActiveSquadronContext.get());
              secondCorrelation.set(CorrelationContext.get());
              return "ok";
            })
        .subscribeOn(Schedulers.parallel())
        .block();

    assertThat(secondActive.get())
        .as("second subscription must not see the first subscription's pin")
        .isNull();
    assertThat(secondCorrelation.get())
        .as("second subscription must not see the first subscription's correlation id")
        .isNull();
  }
}
