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

package de.greluc.krt.profit.basetool.backend.filter;

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
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the originating client IP in a spoofing-resistant way and publishes it as request
 * attributes for downstream consumers (REQ-SEC-011).
 *
 * <p>Runs ahead of {@code ForwardedHeaderFilter}, while the raw peer and the raw {@code
 * X-Forwarded-For} chain are still visible. It honours the chain only when the immediate peer is a
 * trusted proxy, then walks it right-to-left and takes the first untrusted address (the
 * RemoteIpValve algorithm). The result is published as {@link #CLIENT_IP_ATTRIBUTE} and {@link
 * #CLIENT_IP_FORWARDED_ATTRIBUTE}.
 */
@Slf4j
public class ClientIpContextFilter extends OncePerRequestFilter {

  /**
   * Request attribute carrying the resolved client IP as a {@code String}.
   *
   * <p>Always set, even when resolution falls back to the raw peer, so a consumer never has to
   * distinguish "not resolved" from "resolved to the peer".
   */
  public static final String CLIENT_IP_ATTRIBUTE =
      ClientIpContextFilter.class.getName() + ".clientIp";

  /**
   * Request attribute carrying a {@code Boolean}: {@code true} when {@link #CLIENT_IP_ATTRIBUTE}
   * came from a trusted proxy's {@code X-Forwarded-For} chain, {@code false} when it is the raw TCP
   * peer.
   */
  public static final String CLIENT_IP_FORWARDED_ATTRIBUTE =
      ClientIpContextFilter.class.getName() + ".clientIpForwarded";

  /** Standard header carrying the proxy chain; entries left of the peer are client-controlled. */
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  /** Separator used when folding repeated header lines into one chain, per RFC 9110 section 5.3. */
  private static final String HOP_SEPARATOR = ",";

  /** Trusted-proxy matchers compiled once at construction, never re-parsed per request. */
  private final List<IpAddressMatcher> trustedProxyMatchers;

  /**
   * Compiles the trusted-proxy allowlist once.
   *
   * @param trustedProxies exact addresses or CIDR ranges; {@code null} or empty disables {@code
   *     X-Forwarded-For} entirely
   */
  public ClientIpContextFilter(@Nullable List<String> trustedProxies) {
    this.trustedProxyMatchers = compileTrustedProxies(trustedProxies);
  }

  /**
   * Resolves the client IP and publishes it as request attributes before delegating.
   *
   * @param request the incoming request, still carrying the raw forwarded headers
   * @param response the response, passed through untouched
   * @param chain the remaining filter chain
   * @throws ServletException if the downstream chain fails
   * @throws IOException if the downstream chain fails
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    String peer = request.getRemoteAddr();
    String resolved = resolveClientIp(peer, forwardedForChain(request), trustedProxyMatchers);
    if (resolved != null) {
      request.setAttribute(CLIENT_IP_ATTRIBUTE, resolved);
      request.setAttribute(CLIENT_IP_FORWARDED_ATTRIBUTE, !resolved.equals(peer));
    }
    chain.doFilter(request, response);
  }

  /**
   * Joins every {@code X-Forwarded-For} line of the request into one comma-separated chain, so a
   * repeated header is treated the same as a single appended one.
   *
   * @param request the incoming request
   * @return the joined chain, or {@code null} when the header is absent
   */
  @Nullable
  private static String forwardedForChain(@NotNull HttpServletRequest request) {
    Enumeration<String> lines = request.getHeaders(FORWARDED_FOR_HEADER);
    if (lines == null || !lines.hasMoreElements()) {
      return null;
    }
    StringJoiner joined = new StringJoiner(HOP_SEPARATOR);
    while (lines.hasMoreElements()) {
      joined.add(lines.nextElement());
    }
    return joined.toString();
  }

  /**
   * Applies the RemoteIpValve algorithm to a raw peer plus a raw {@code X-Forwarded-For} chain.
   *
   * @param remoteAddr the raw TCP peer; {@code null} only for a malformed request
   * @param xffHeader the raw {@code X-Forwarded-For} header, or {@code null}/blank when absent
   * @param trustedProxies the compiled trusted-proxy matchers; never {@code null}
   * @return the first untrusted hop walking right-to-left when the peer is trusted, otherwise
   *     {@code remoteAddr}; {@code null} only when {@code remoteAddr} is
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
      if (!candidate.isEmpty() && !isTrusted(candidate, trustedProxies)) {
        return candidate;
      }
    }
    return remoteAddr;
  }

  /**
   * Tests an address against the trusted-proxy allowlist.
   *
   * @param ip the candidate address; never {@code null}
   * @param trustedProxies the compiled matchers
   * @return {@code true} iff {@code ip} is an address inside a trusted range; a non-IP token such
   *     as {@code "unknown"} is untrusted
   */
  private static boolean isTrusted(
      @NotNull String ip, @NotNull List<IpAddressMatcher> trustedProxies) {
    if (!looksLikeIpLiteral(ip)) {
      return false;
    }
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
   * Cheaply rejects anything that cannot be an IPv4 or IPv6 literal, without allocating; {@link
   * IpAddressMatcher} still does the real parsing.
   *
   * @param candidate the hop to screen; never {@code null}
   * @return {@code true} when every character could appear in an address literal
   */
  private static boolean looksLikeIpLiteral(@NotNull String candidate) {
    for (int i = 0; i < candidate.length(); i++) {
      char c = candidate.charAt(i);
      boolean plausible =
          (c >= '0' && c <= '9')
              || (c >= 'a' && c <= 'f')
              || (c >= 'A' && c <= 'F')
              || c == '.'
              || c == ':'
              || c == '%';
      if (!plausible) {
        return false;
      }
    }
    return !candidate.isEmpty();
  }

  /**
   * Compiles configured entries into matchers, dropping the ones that would re-open the spoof.
   *
   * @param entries the raw trusted-proxy values; may be {@code null}.
   * @return an immutable list of matchers; empty when nothing valid is configured, which disables
   *     {@code X-Forwarded-For} entirely rather than trusting it.
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
            "Invalid app.rate-limit.trusted-proxies entry '{}'; ignoring. Reason: {}",
            entry,
            ex.getMessage());
      }
    }
    return Collections.unmodifiableList(matchers);
  }
}
