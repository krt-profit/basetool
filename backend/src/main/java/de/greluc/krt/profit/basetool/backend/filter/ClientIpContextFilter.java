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

import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the originating client IP in a spoofing-resistant way and publishes it as a request
 * attribute for every downstream consumer (REQ-SEC-011).
 *
 * <p><b>Why this filter has to exist at all.</b> The backend runs with {@code
 * server.forward-headers-strategy: none} and re-registers Spring's {@code ForwardedHeaderFilter}
 * one ordering slot later ({@code ForwardedHeaderConfig}). That looks like a detour, and it is not:
 * with the {@code framework} strategy Spring Boot pins {@code ForwardedHeaderFilter} to {@link
 * Ordered#HIGHEST_PRECEDENCE} ({@code Integer.MIN_VALUE}), which no servlet filter can precede, and
 * that filter rewrites the request before anything else sees it. Measured with the chain {@code
 * X-Forwarded-For: 9.9.9.9, 203.0.113.7} arriving from peer {@code 172.28.0.5} (pinned by {@code
 * ForwardedHeaderRewriteTest}), a filter placed after it observes:
 *
 * <ul>
 *   <li>{@code getRemoteAddr()} rewritten to {@code 9.9.9.9} — the <em>leftmost</em> entry;
 *   <li>{@code getHeader("X-Forwarded-For")} returning {@code null} — the header is hidden.
 * </ul>
 *
 * <p>Both halves matter. A downstream filter cannot recover the real peer, because it has been
 * overwritten with a client-supplied value, and it cannot re-derive it from the chain, because the
 * chain is gone. nginx-proxy-manager <em>appends</em> the true peer on the right ({@code
 * $proxy_add_x_forwarded_for}), so the leftmost entry is exactly the part an attacker controls:
 * rotating it mints a fresh rate-limit bucket per request. This is dormant today — the backend is
 * reachable only from the frontend, which relays a single, already-validated entry — and goes live
 * the moment a public vhost puts an appending proxy in front of it.
 *
 * <p><b>What it does instead.</b> Running at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of the
 * re-registered {@code ForwardedHeaderFilter}, it sees the raw peer and the raw chain, and applies
 * the RemoteIpValve algorithm: honour {@code X-Forwarded-For} only when the immediate peer is a
 * configured trusted proxy, then walk the chain right-to-left skipping trusted hops and take the
 * first untrusted address. A forged entry sits to the left of the proxy-appended truth and is never
 * reached. The result is published as {@link #CLIENT_IP_ATTRIBUTE} and {@link
 * #CLIENT_IP_FORWARDED_ATTRIBUTE}; {@code ForwardedHeaderFilter} still runs afterwards and rebuilds
 * scheme, host and {@code getRemoteAddr()} for problem-detail {@code instance} URIs and redirects
 * exactly as before.
 *
 * <p>This mirrors the frontend filter of the same name (finding SEC-02); the two modules hit the
 * same trap for different reasons and are deliberately kept structurally identical.
 */
@Slf4j
public class ClientIpContextFilter extends OncePerRequestFilter implements Ordered {

  /**
   * Request attribute carrying the resolved client IP as a {@code String}.
   *
   * <p>Always set, even when resolution falls back to the raw peer, so a consumer never has to
   * distinguish "not resolved" from "resolved to the peer".
   */
  public static final String CLIENT_IP_ATTRIBUTE =
      ClientIpContextFilter.class.getName() + ".clientIp";

  /**
   * Request attribute carrying a {@code Boolean} that is {@code true} when the value in {@link
   * #CLIENT_IP_ATTRIBUTE} came from a trusted proxy's {@code X-Forwarded-For} chain and {@code
   * false} when it is the raw TCP peer.
   *
   * <p>Consumers use this for the {@code key_source} metric label: behind a reverse proxy a
   * sustained {@code false} means per-client bucketing has silently collapsed onto one address.
   */
  public static final String CLIENT_IP_FORWARDED_ATTRIBUTE =
      ClientIpContextFilter.class.getName() + ".clientIpForwarded";

  /** Standard header carrying the proxy chain; entries left of the peer are client-controlled. */
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  /** Trusted-proxy matchers compiled once at construction, never re-parsed per request. */
  private final List<IpAddressMatcher> trustedProxyMatchers;

  /**
   * Compiles the trusted-proxy allowlist once.
   *
   * <p>The list is read from {@code app.rate-limit.trusted-proxies} rather than from a key of this
   * filter's own: that is the value production already sets and REQ-SEC-011 documents, and
   * splitting it would create a second place to get the proxy network wrong.
   *
   * @param properties the validated rate-limit configuration supplying the trusted-proxy list;
   *     never {@code null}.
   */
  public ClientIpContextFilter(@NotNull RateLimitProperties properties) {
    this.trustedProxyMatchers = compileTrustedProxies(properties.getTrustedProxies());
  }

  /**
   * Runs first in the chain so the raw peer and the raw {@code X-Forwarded-For} are still intact.
   *
   * @return {@link Ordered#HIGHEST_PRECEDENCE}, one slot ahead of the re-registered {@code
   *     ForwardedHeaderFilter}.
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  /**
   * Resolves the client IP and publishes it as request attributes before delegating.
   *
   * <p>Nothing has to be cleaned up on the way out: the values live on the request, not on a
   * thread-local, so they cannot bleed onto a pooled or virtual thread.
   *
   * @param request the incoming request, still carrying the raw forwarded headers.
   * @param response the response, passed through untouched.
   * @param chain the remaining filter chain.
   * @throws ServletException if the downstream chain fails.
   * @throws IOException if the downstream chain fails.
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    String peer = request.getRemoteAddr();
    String resolved =
        resolveClientIp(peer, request.getHeader(FORWARDED_FOR_HEADER), trustedProxyMatchers);
    request.setAttribute(CLIENT_IP_ATTRIBUTE, resolved);
    request.setAttribute(CLIENT_IP_FORWARDED_ATTRIBUTE, resolved != null && !resolved.equals(peer));
    chain.doFilter(request, response);
  }

  /**
   * Applies the RemoteIpValve algorithm to a raw peer plus a raw {@code X-Forwarded-For} chain.
   *
   * <p>Static and package-private so the chain semantics can be tested exhaustively without a
   * servlet container.
   *
   * @param remoteAddr the raw TCP peer address; may be {@code null} only for a malformed request.
   * @param xffHeader the raw {@code X-Forwarded-For} header, or {@code null}/blank when absent.
   * @param trustedProxies the compiled trusted-proxy matchers; never {@code null}.
   * @return the first untrusted address found walking the chain right-to-left when the peer is a
   *     trusted proxy; otherwise {@code remoteAddr}. {@code null} only when {@code remoteAddr} is.
   */
  @Nullable
  static String resolveClientIp(
      @Nullable String remoteAddr,
      @Nullable String xffHeader,
      @NotNull List<IpAddressMatcher> trustedProxies) {
    if (remoteAddr == null) {
      return null;
    }
    // A direct connection can never influence attribution: its header is not trusted, so the peer
    // wins. This is the branch that protects a container reached around the proxy.
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
    // Every hop was itself a trusted proxy, so the chain carries no client address.
    return remoteAddr;
  }

  /**
   * Tests an address against the trusted-proxy allowlist.
   *
   * @param ip the candidate address; never {@code null}.
   * @param trustedProxies the compiled matchers.
   * @return {@code true} iff {@code ip} parses as an address inside a trusted range. A non-IP token
   *     such as the {@code "unknown"} some proxies emit is treated as untrusted rather than
   *     throwing.
   */
  private static boolean isTrusted(
      @NotNull String ip, @NotNull List<IpAddressMatcher> trustedProxies) {
    for (IpAddressMatcher matcher : trustedProxies) {
      try {
        if (matcher.matches(ip)) {
          return true;
        }
      } catch (IllegalArgumentException ex) {
        // Unparseable candidate: it cannot be one of our proxies, so it stays untrusted.
      }
    }
    return false;
  }

  /**
   * Compiles configured entries into matchers, dropping the ones that would re-open the spoof.
   *
   * @param entries the raw {@code app.rate-limit.trusted-proxies} values; may be {@code null}.
   * @return an immutable list of matchers; empty when nothing valid is configured, which disables
   *     {@code X-Forwarded-For} entirely rather than trusting it.
   */
  private static List<IpAddressMatcher> compileTrustedProxies(@Nullable List<String> entries) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    List<IpAddressMatcher> matchers = new ArrayList<>(entries.size());
    for (String entry : entries) {
      // "*" is rejected on purpose: blanket trust lets any client spoof the header.
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
