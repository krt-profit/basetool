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

package de.greluc.krt.profit.basetool.frontend.logging;

import de.greluc.krt.profit.basetool.frontend.config.ClientIpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.springframework.core.Ordered;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the originating client IP in a spoofing-resistant way and holds it in {@link
 * ClientIpContext} for the duration of the request.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE}, before {@code ForwardedHeaderFilter} rewrites the
 * headers, so it sees the raw TCP peer and {@code X-Forwarded-For} chain. The header is honoured
 * only when the peer is a trusted proxy ({@link ClientIpProperties#getTrustedProxies()}), and the
 * chain is walked right-to-left, skipping trusted hops. The context is cleared in a {@code
 * finally}.
 */
@Slf4j
@Component
public class ClientIpContextFilter extends OncePerRequestFilter implements Ordered {

  /** Standard header carrying the proxy chain; the leftmost entry is client-controlled. */
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  /** Trusted-proxy matchers compiled once from {@link ClientIpProperties#getTrustedProxies()}. */
  private final List<IpAddressMatcher> trustedProxyMatchers;

  /**
   * Compiles the configured trusted-proxy allowlist into {@link IpAddressMatcher} instances once at
   * construction.
   *
   * @param properties the validated client-IP configuration; never {@code null}.
   */
  public ClientIpContextFilter(@NotNull ClientIpProperties properties) {
    this.trustedProxyMatchers = compileTrustedProxies(properties.trustedProxies());
  }

  /**
   * Returns {@link Ordered#HIGHEST_PRECEDENCE}, so this filter runs before {@code
   * ForwardedHeaderFilter} rewrites {@code X-Forwarded-For} and {@code getRemoteAddr()}.
   *
   * @return the filter order
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    ClientIpContext.set(
        resolveClientIp(request.getRemoteAddr(), forwardedForChain(request), trustedProxyMatchers));
    try {
      chain.doFilter(request, response);
    } finally {
      ClientIpContext.clear();
    }
  }

  /**
   * Joins every {@code X-Forwarded-For} line of the request into one chain, so a repeated header is
   * not reduced to its first, client-supplied line.
   *
   * @param request the incoming request.
   * @return the joined chain, or {@code null} when the header is absent entirely.
   */
  @Nullable
  private static String forwardedForChain(@NotNull HttpServletRequest request) {
    Enumeration<String> lines = request.getHeaders(FORWARDED_FOR_HEADER);
    if (lines == null || !lines.hasMoreElements()) {
      return null;
    }
    StringJoiner joined = new StringJoiner(",");
    while (lines.hasMoreElements()) {
      joined.add(lines.nextElement());
    }
    return joined.toString();
  }

  /**
   * Resolves the client IP from the raw TCP peer and {@code X-Forwarded-For} chain.
   *
   * <p>The header is honoured only when the peer is a trusted proxy; the chain is then walked
   * right-to-left, skipping trusted hops, and the first untrusted address is returned.
   *
   * @param remoteAddr the raw TCP peer address; may be {@code null} only for a malformed request.
   * @param xffHeader the raw {@code X-Forwarded-For} header, or {@code null}/blank when absent.
   * @param trustedProxies the compiled trusted-proxy matchers; never {@code null}.
   * @return the resolved client IP, or {@code remoteAddr} when no trusted-proxy-relayed client
   *     address is available; {@code null} only when {@code remoteAddr} is {@code null}.
   */
  @Nullable
  static String resolveClientIp(
      @Nullable String remoteAddr,
      @Nullable String xffHeader,
      @NotNull List<IpAddressMatcher> trustedProxies) {
    if (remoteAddr == null) {
      return null;
    }
    if (xffHeader == null || xffHeader.isBlank() || !isTrusted(remoteAddr, trustedProxies)) {
      return remoteAddr;
    }
    String[] hops = xffHeader.split(",");
    for (int i = hops.length - 1; i >= 0; i--) {
      String candidate = hops[i].trim();
      if (candidate.isEmpty()) {
        continue;
      }
      if (!isTrusted(candidate, trustedProxies)) {
        return candidate;
      }
    }
    return remoteAddr;
  }

  /**
   * Tests whether {@code ip} matches any trusted-proxy matcher. A non-IP token (e.g. {@code
   * "unknown"} some proxies emit) never matches a CIDR/IP rule and is treated as untrusted.
   *
   * @param ip the candidate address to test; never {@code null}.
   * @param trustedProxies the compiled trusted-proxy matchers.
   * @return {@code true} iff {@code ip} falls inside a trusted-proxy range.
   */
  private static boolean isTrusted(
      @NotNull String ip, @NotNull List<IpAddressMatcher> trustedProxies) {
    for (IpAddressMatcher matcher : trustedProxies) {
      try {
        if (matcher.matches(ip)) {
          return true;
        }
      } catch (IllegalArgumentException ignored) {
      }
    }
    return false;
  }

  /**
   * Compiles the configured trusted-proxy entries into {@link IpAddressMatcher} instances, skipping
   * blank entries and the blanket {@code "*"} (which would re-open the spoof) and warning on any
   * unparseable entry rather than failing startup.
   *
   * @param entries the raw {@code app.client-ip.trusted-proxies} values; may be {@code null}/empty.
   * @return an immutable list of compiled matchers; empty when nothing valid is configured.
   */
  @NotNull
  @UnmodifiableView
  private static List<IpAddressMatcher> compileTrustedProxies(@Nullable List<String> entries) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    List<IpAddressMatcher> matchers = new ArrayList<>(entries.size());
    for (String entry : entries) {
      if (entry == null || entry.isBlank() || "*".equals(entry)) {
        continue;
      }
      try {
        matchers.add(new IpAddressMatcher(entry));
      } catch (IllegalArgumentException ex) {
        log.warn(
            "Invalid app.client-ip.trusted-proxies entry '{}'; ignoring. Reason: {}",
            entry,
            ex.getMessage());
      }
    }
    return Collections.unmodifiableList(matchers);
  }
}
