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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Runs work for a member as a relayed exchange request of a client runs (REQ-XCH-009, REQ-XCH-021):
 * under the member's reduced exchange authorities for the client's capabilities, without the
 * browser's active org-unit pin and with request attributes of its own, so nothing the browser
 * session resolved earlier in the request decides it. A staged change set the member previews or
 * confirms in the browser is thereby decided as the gateway's relay decides it.
 */
@Component
@RequiredArgsConstructor
public class ExchangeActingMemberScope {

  /** Assembles the member's reduced exchange authorities. */
  private final ActingMemberAuthorities actingMemberAuthorities;

  /**
   * Runs work as the client's relayed request for the member.
   *
   * @param caller the member, client and installation
   * @param capabilities the client's capabilities, whose scopes the request carries
   * @param work the work
   * @param <T> the work's result
   * @return the work's result
   * @throws org.springframework.security.access.AccessDeniedException when the member is not live
   */
  public <T> T call(
      @NotNull ExchangeCaller caller,
      @NotNull Collection<ExchangeCapability> capabilities,
      @NotNull Supplier<T> work) {
    ActingMemberFilter.ActingMemberAuthentication acting =
        new ActingMemberFilter.ActingMemberAuthentication(
            caller.member(),
            actingMemberAuthorities.exchangeAuthoritiesFor(
                caller.member(), capabilities.stream().map(ExchangeCapability::getScope).toList()),
            caller.clientId(),
            caller.installationKey(),
            null);
    RequestAttributes originalRequest = RequestContextHolder.getRequestAttributes();
    if (originalRequest instanceof ServletRequestAttributes servlet) {
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new RelayedRequest(servlet.getRequest())));
    }
    try {
      return ActingMemberFilter.callAs(acting, work);
    } finally {
      RequestContextHolder.setRequestAttributes(originalRequest);
    }
  }

  /**
   * The browser's request as a relayed exchange request sees it: without the active org-unit pin,
   * which the gateway never relays, and with an attribute store of its own.
   */
  private static final class RelayedRequest extends HttpServletRequestWrapper {

    /** The attributes set while the work runs. */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * Wraps the browser's request.
     *
     * @param request the browser's request
     */
    RelayedRequest(@NotNull HttpServletRequest request) {
      super(request);
    }

    @Override
    public @Nullable String getHeader(String name) {
      return RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER.equalsIgnoreCase(name)
          ? null
          : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER.equalsIgnoreCase(name)
          ? Collections.emptyEnumeration()
          : super.getHeaders(name);
    }

    @Override
    public @Nullable Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
      return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
      attributes.remove(name);
    }
  }
}
