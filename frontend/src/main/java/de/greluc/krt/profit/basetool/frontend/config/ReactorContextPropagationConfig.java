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

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import reactor.core.publisher.Hooks;

/**
 * Wires Reactor's automatic context propagation so that custom {@code ThreadLocal} holders ({@link
 * ActiveSquadronContext} and {@link CorrelationContext}) flow into {@code WebClient} exchange
 * filters that execute on Reactor worker threads.
 *
 * <p><b>Why this exists:</b> Spring's {@code WebClient} runs each exchange on a Reactor-Netty
 * worker thread, not on the originating servlet thread. Classic {@link ThreadLocal} values are not
 * copied across threads — they live only on the thread that called {@code set()}. The {@link
 * de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter#relayActiveSquadron()}
 * lambda would therefore read {@link ActiveSquadronContext#get()} == {@code null} on the Reactor
 * worker, silently drop the {@code X-Active-Org-Unit-Id} header, and the backend would fall through
 * to its "all OrgUnits" default — leaking other squadrons' rows into a pinned admin's Lager view.
 * The same mechanism breaks {@link CorrelationContext} propagation: outbound backend calls would
 * log a different correlation id than the inbound frontend request, breaking the audit-trail join
 * CLAUDE.md "Logging" relies on.
 *
 * <p><b>The fix:</b> Micrometer's context-propagation library (transitive dependency of Spring Boot
 * 4 + Reactor 3.7+) exposes a {@code ThreadLocalAccessor} SPI. When {@link
 * Hooks#enableAutomaticContextPropagation()} is on and accessors are registered on {@link
 * ContextRegistry#getInstance()}, Reactor automatically snapshots the registered thread-locals when
 * a {@code Mono}/{@code Flux} is assembled on a servlet thread and restores them on whichever
 * worker thread the operator runs on. The original {@code ThreadLocal} stays untouched on the
 * servlet thread; the Reactor-side restoration is short-lived (cleared after the operator runs) so
 * there is no cross-request bleed.
 *
 * <p>Registration is idempotent — calling {@code registerThreadLocalAccessor(key, ...)} a second
 * time replaces the previous accessor for that key — but we still guard via {@code @PostConstruct}
 * ordering to match Spring's bean lifecycle.
 *
 * <p><b>Why not let Spring Boot auto-enable this:</b> Spring Boot 4 calls {@link
 * Hooks#enableAutomaticContextPropagation()} automatically when {@code
 * io.micrometer:context-propagation} is on the classpath. The hook activation is therefore in
 * principle a no-op here — but the {@link ThreadLocalAccessor} registrations are not auto-wired;
 * each custom {@code ThreadLocal} must register itself. We call the hook activation explicitly
 * anyway so the bug is impossible to regress if a future Spring Boot upgrade removes the
 * auto-configuration.
 */
@Configuration
@Slf4j
public class ReactorContextPropagationConfig {

  /**
   * Context-registry key under which the active OrgUnit id is propagated through Reactor pipelines.
   * Same string is consumed by the {@code ThreadLocalAccessor} below; not used as a header name
   * (see {@code ActiveSquadronRelayFilter.ACTIVE_ORG_UNIT_HEADER} for that).
   */
  public static final String ACTIVE_ORG_UNIT_CONTEXT_KEY = "iridium.activeOrgUnitId";

  /**
   * Context-registry key under which the per-request correlation id is propagated through Reactor
   * pipelines. Matches the existing {@code CorrelationContext} thread-local owner.
   */
  public static final String CORRELATION_CONTEXT_KEY = "iridium.correlationId";

  /**
   * Context-registry key under which the user's resolved UI locale ({@link
   * org.springframework.context.i18n.LocaleContextHolder}) is propagated through Reactor pipelines,
   * feeding the {@code Accept-Language} relay of {@link
   * de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter} (#435).
   */
  public static final String USER_LOCALE_CONTEXT_KEY = "iridium.userLocale";

  /**
   * Context-registry key under which the resolved originating client IP ({@link ClientIpContext})
   * is propagated through Reactor pipelines, feeding the {@code X-Forwarded-For} relay of {@link
   * de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter} so the backend's per-IP
   * rate limiter sees the real client rather than the frontend container (security audit DOS-1).
   */
  public static final String CLIENT_IP_CONTEXT_KEY = "iridium.clientIp";

  /**
   * Activates {@link Hooks#enableAutomaticContextPropagation()} and registers {@code
   * ThreadLocalAccessor}s for {@link ActiveSquadronContext} and {@link CorrelationContext} on the
   * global {@link ContextRegistry}. Runs once at bean-init time; the registry is a process-wide
   * singleton, so the accessors stay registered for the JVM lifetime.
   *
   * <p>Both accessors implement the {@code <V> getValue / setValue / removeValue} triple required
   * by the SPI. The setter intentionally tolerates {@code null} — when Reactor restores a snapshot
   * that did not capture a value, the SPI feeds {@code null} into the setter, and the underlying
   * thread-local's {@code set(null)} on a String/UUID would persist a null value rather than clear;
   * we forward to the holder's clear semantics explicitly.
   */
  @PostConstruct
  void enableContextPropagation() {
    Hooks.enableAutomaticContextPropagation();
    ContextRegistry registry = ContextRegistry.getInstance();

    registry.registerThreadLocalAccessor(
        ACTIVE_ORG_UNIT_CONTEXT_KEY,
        ActiveSquadronContext::get,
        (UUID value) -> {
          if (value == null) {
            ActiveSquadronContext.clear();
          } else {
            ActiveSquadronContext.set(value);
          }
        },
        ActiveSquadronContext::clear);

    registry.registerThreadLocalAccessor(
        CORRELATION_CONTEXT_KEY,
        CorrelationContext::get,
        (String value) -> {
          if (value == null || value.isBlank()) {
            CorrelationContext.clear();
          } else {
            CorrelationContext.set(value);
          }
        },
        CorrelationContext::clear);

    registry.registerThreadLocalAccessor(
        USER_LOCALE_CONTEXT_KEY,
        () ->
            LocaleContextHolder.getLocaleContext() != null
                ? LocaleContextHolder.getLocaleContext().getLocale()
                : null,
        (Locale value) -> {
          if (value == null) {
            LocaleContextHolder.resetLocaleContext();
          } else {
            LocaleContextHolder.setLocale(value);
          }
        },
        LocaleContextHolder::resetLocaleContext);

    registry.registerThreadLocalAccessor(
        CLIENT_IP_CONTEXT_KEY,
        ClientIpContext::get,
        (String value) -> {
          if (value == null || value.isBlank()) {
            ClientIpContext.clear();
          } else {
            ClientIpContext.set(value);
          }
        },
        ClientIpContext::clear);

    log.info(
        "Reactor automatic context propagation enabled; registered ThreadLocalAccessors for "
            + "ActiveSquadronContext ({}), CorrelationContext ({}), the user locale ({}), the "
            + "client IP ({}).",
        ACTIVE_ORG_UNIT_CONTEXT_KEY,
        CORRELATION_CONTEXT_KEY,
        USER_LOCALE_CONTEXT_KEY,
        CLIENT_IP_CONTEXT_KEY);
  }
}
