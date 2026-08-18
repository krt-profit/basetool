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
import org.springframework.core.Ordered;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that resolves the originating client IP in a <b>spoofing-resistant</b> way and
 * snapshots it into {@link ClientIpContext} for the duration of the request, clearing it on the way
 * out (finding SEC-02).
 *
 * <p><b>Why this does NOT just read {@code getRemoteAddr()}:</b> the frontend relies on Spring's
 * {@code ForwardedHeaderFilter} to rebuild {@code X-Forwarded-Host}/{@code -Proto} so the OAuth2
 * redirect URI and HSTS are built against the external origin. But that filter takes the
 * <em>leftmost</em> {@code X-Forwarded-For} entry with <b>no</b> trusted-proxy check, and
 * nginx-proxy-manager appends the real peer on the <em>right</em> — so the leftmost entry is
 * whatever the client put there. Reading {@code getRemoteAddr()} after that filter therefore yields
 * an attacker-chosen value, letting an unauthenticated caller mint a fresh per-IP rate-limit bucket
 * per request by rotating a forged {@code X-Forwarded-For} (SEC-02). The ingest module sidesteps
 * this with the {@code native}/RemoteIpValve strategy (INGEST-RATELIMIT-1); the frontend cannot,
 * because {@code native} does not reconstruct {@code X-Forwarded-Host} for the OAuth2 redirect.
 *
 * <p><b>What it does instead:</b> it runs at {@link Ordered#HIGHEST_PRECEDENCE} — <em>before</em>
 * {@code ForwardedHeaderFilter} (re-registered one slot later by {@code ForwardedHeaderConfig},
 * with {@code server.forward-headers-strategy: none}, because Spring Boot pins the auto-registered
 * filter to {@code Integer.MIN_VALUE}, which nothing can precede) consumes/rewrites the headers —
 * so it sees the raw TCP peer ({@code getRemoteAddr()} = the proxy) and the raw {@code
 * X-Forwarded-For} chain. It then applies the RemoteIpValve algorithm: honour {@code
 * X-Forwarded-For} only when the peer is a configured trusted proxy ({@link
 * ClientIpProperties#getTrustedProxies()}), and walk the chain right-to-left skipping trusted hops,
 * taking the first untrusted address as the client. Because the proxy appends the true peer on the
 * right, a client-supplied (left-side) forged entry is never reached. {@code ForwardedHeaderFilter}
 * still runs afterwards and rewrites scheme/host/remote-addr for OAuth2 and HSTS exactly as before
 * — only the rate-limit attribution changes.
 *
 * <p>The resolved value is held in a {@link ClientIpContext} thread-local because {@link
 * ClientIpRelayFilter} runs on Netty reactor threads where {@code RequestContextHolder} is not
 * bound; Reactor's automatic context propagation carries it across the hop. The {@code finally}
 * cleanup prevents bleed-through onto pooled or virtual threads.
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
   * Filter order: {@link Ordered#HIGHEST_PRECEDENCE} so this filter runs before {@code
   * ForwardedHeaderFilter} (re-registered at {@code HIGHEST_PRECEDENCE + 1} by {@code
   * ForwardedHeaderConfig}) consumes the {@code X-Forwarded-For} header and rewrites {@code
   * getRemoteAddr()}. Only then is the raw proxy chain visible for trusted-proxy-aware resolution.
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
   * Folds every {@code X-Forwarded-For} line of the request into one chain.
   *
   * <p>HTTP permits a header to be repeated. Some proxies append to the existing line ({@code
   * $proxy_add_x_forwarded_for}), others add a second line (HAProxy's {@code http-request
   * add-header}, several CDNs, any chain where two hops each add their own). {@code getHeader}
   * returns only the <b>first</b> line, so on the add-header shape it hands back the
   * client-supplied entry and the walk returns exactly the value this filter distrusts. Tomcat's
   * {@code RemoteIpFilter} concatenates {@code getHeaders} for the same reason. Today's edge
   * normalises duplicates, so this is defence against a topology change.
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
   * Resolves the originating client IP from the raw TCP peer and {@code X-Forwarded-For} chain
   * using the RemoteIpValve algorithm (package-private + static so it is unit-testable without a
   * servlet container).
   *
   * <p>The header is honoured <b>only</b> when the immediate peer is a trusted proxy; otherwise the
   * peer itself is returned and the (untrusted) header is ignored. When honoured, the chain is
   * walked right-to-left, skipping trusted-proxy hops, and the first untrusted address is returned
   * — the real client, which the proxy appended on the right. A client-supplied forged entry sits
   * to the left of that and is never reached, so it cannot influence the result.
   *
   * @param remoteAddr the raw TCP peer address ({@code request.getRemoteAddr()} before {@code
   *     ForwardedHeaderFilter}); may be {@code null} only for a malformed request.
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
    // A direct connection (dev, or an attacker reaching the container directly) can never influence
    // attribution: its X-Forwarded-For is not trusted, so the raw peer is used.
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
    // Every hop was itself a trusted proxy (no client address present): fall back to the peer.
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
      } catch (IllegalArgumentException ex) {
        // Unparseable candidate (not an IP literal): cannot be a trusted proxy.
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
