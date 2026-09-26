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
 * Enables Reactor's automatic context propagation and registers {@code ThreadLocalAccessor}s so
 * {@link ActiveSquadronContext} and {@link CorrelationContext} are visible in {@code WebClient}
 * exchange filters running on Reactor worker threads.
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
   * Context-registry key under which the user's resolved UI locale is propagated through Reactor
   * pipelines for the {@code Accept-Language} relay of {@link
   * de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter}.
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
   * Activates {@link Hooks#enableAutomaticContextPropagation()} and registers the thread-local
   * accessors on the global {@link ContextRegistry} once at bean initialisation.
   *
   * <p>A {@code null} restored value clears the holder.
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
