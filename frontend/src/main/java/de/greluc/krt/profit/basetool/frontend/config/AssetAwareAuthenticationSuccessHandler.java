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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * {@link AuthenticationSuccessHandler} that delegates to {@link
 * SavedRequestAwareAuthenticationSuccessHandler} for normal post-login navigation but filters out
 * saved requests whose target URL is a static asset rather than a user-initiated page navigation.
 *
 * <p><strong>Why this exists.</strong> Spring Security's {@link HttpSessionRequestCache} stores the
 * URL of the request that triggered authentication so the user can be sent back there after login.
 * Browser DevTools and various browser extensions (Sentry Replay, LogRocket, etc.) fire background
 * {@code .map} sourcemap lookups and similar non-HTML asset requests while the user is logged out.
 * If such a request lands in the saved-request slot, the default success handler will redirect the
 * user to the asset URL after they successfully log in — producing a 404 landing page instead of
 * the home page or the page they originally came from. Static-asset paths are also permit-listed in
 * {@link SecurityConfig} so they never reach the auth entry point in the first place; this handler
 * is the defense-in-depth net for any future asset path we forget to enumerate or any other
 * non-HTML request that somehow ends up in the saved-request slot.
 *
 * <p>When the saved URL's path starts with {@code /sm/} or ends in one of {@link
 * #ASSET_EXTENSIONS}, the saved request is removed and the response is redirected to the context
 * root ({@code /}). Otherwise the wrapped {@link SavedRequestAwareAuthenticationSuccessHandler}
 * runs unchanged — its default target is also {@code /} when no saved request exists, so the caller
 * never lands on a blank page.
 */
@Slf4j
public class AssetAwareAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  /**
   * Lower-cased path-suffix list (file extensions) that identifies a request as a static asset
   * rather than a user-initiated page navigation. Compared via {@link String#endsWith(String)}
   * against the URL's path component after stripping any query string. The {@code .json} entry
   * matches both real JSON endpoints and the sourcemap-style {@code .json} sidecar files some
   * tooling emits; this is acceptable because no production navigation path ends in {@code .json}.
   */
  static final List<String> ASSET_EXTENSIONS =
      List.of(
          ".map", ".ico", ".png", ".jpg", ".jpeg", ".svg", ".gif", ".webp", ".css", ".js", ".woff",
          ".woff2", ".ttf", ".otf", ".eot", ".json", ".txt");

  /**
   * Path prefix used by browser-extension and replay-tool sourcemap requests (e.g. Sentry Replay's
   * {@code /sm/<hash>.map} pattern). Any saved URL whose path starts with this prefix is treated as
   * an asset request regardless of extension.
   */
  static final String SOURCEMAP_PREFIX = "/sm/";

  private final RequestCache requestCache;
  private final AuthenticationSuccessHandler delegate;

  /**
   * Builds a handler around the caller's request cache and a fresh {@link
   * SavedRequestAwareAuthenticationSuccessHandler} whose default target URL is {@code /} and which
   * reads that <em>same</em> cache. This is the constructor used by {@link SecurityConfig} in
   * production, and the cache it is given is the one the filter chain saves into.
   *
   * <p>It used to take no argument and build a private {@code new HttpSessionRequestCache()}, with
   * the delegate quietly building a third. All three agreed only because they used the same default
   * session attribute — so the moment one of them grew a {@link
   * org.springframework.security.web.util.matcher.RequestMatcher} (WP-F 11: save only real
   * navigations, so a logged-out browser's background calls mint no session), the others would have
   * gone on saving and replaying everything.
   *
   * @param requestCache the shared cache the chain saves into; never {@code null}
   */
  public AssetAwareAuthenticationSuccessHandler(@NotNull RequestCache requestCache) {
    this(requestCache, defaultDelegate(requestCache));
  }

  /**
   * Constructor for tests that need to inject a mock {@link RequestCache} and a mock delegate so
   * the handler can be exercised without spinning up a real Spring Security context.
   *
   * @param requestCache the request cache used to look up and remove the saved request; never
   *     {@code null}
   * @param delegate the success handler invoked when the saved request is absent or points to a
   *     non-asset URL; never {@code null}
   */
  AssetAwareAuthenticationSuccessHandler(
      @NotNull RequestCache requestCache, @NotNull AuthenticationSuccessHandler delegate) {
    this.requestCache = requestCache;
    this.delegate = delegate;
  }

  /**
   * Builds the wrapped default handler, pointed at the same request cache so the replay reads what
   * the chain saved.
   *
   * @param requestCache the shared cache; never {@code null}
   * @return the delegate whose default target URL is {@code /}
   */
  private static SavedRequestAwareAuthenticationSuccessHandler defaultDelegate(
      @NotNull RequestCache requestCache) {
    SavedRequestAwareAuthenticationSuccessHandler handler =
        new SavedRequestAwareAuthenticationSuccessHandler();
    handler.setRequestCache(requestCache);
    handler.setDefaultTargetUrl("/");
    handler.setAlwaysUseDefaultTargetUrl(false);
    return handler;
  }

  /**
   * Inspects the cached saved request: if it points to an asset-like URL, removes it from the cache
   * and redirects to the context root; otherwise delegates to the wrapped {@link
   * SavedRequestAwareAuthenticationSuccessHandler}.
   *
   * @param request the current HTTP request; used to look up the saved request and to derive the
   *     servlet context path for the home-page redirect
   * @param response the HTTP response to which the redirect is written when the saved URL is
   *     filtered
   * @param authentication the successful authentication; forwarded verbatim to the delegate
   * @throws IOException if writing the redirect or invoking the delegate fails
   * @throws ServletException if the delegate raises a servlet-layer exception
   */
  @Override
  public void onAuthenticationSuccess(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Authentication authentication)
      throws IOException, ServletException {
    SavedRequest saved = requestCache.getRequest(request, response);
    if (saved != null && isAssetLikePath(saved.getRedirectUrl())) {
      log.debug(
          "[oauth2-success] dropping asset-like saved request and redirecting to /: savedUrl={}",
          saved.getRedirectUrl());
      requestCache.removeRequest(request, response);
      response.sendRedirect(request.getContextPath() + "/");
      return;
    }
    delegate.onAuthenticationSuccess(request, response, authentication);
  }

  /**
   * Returns {@code true} when the given URL points to a static asset rather than a user-initiated
   * page navigation. A URL qualifies as asset-like when its path either starts with {@link
   * #SOURCEMAP_PREFIX} or ends with one of {@link #ASSET_EXTENSIONS} after the query string has
   * been stripped. {@code null} inputs and unparsable URIs return {@code false} so a malformed
   * saved request never short-circuits the normal login redirect.
   *
   * @param url the absolute or relative URL stored in the saved request; may be {@code null}
   * @return {@code true} if the URL's path is recognised as a static-asset path; {@code false}
   *     otherwise
   */
  static boolean isAssetLikePath(@Nullable String url) {
    if (url == null) {
      return false;
    }
    String path;
    try {
      path = URI.create(url).getPath();
    } catch (IllegalArgumentException ex) {
      return false;
    }
    if (path == null || path.isEmpty()) {
      return false;
    }
    String lowerPath = path.toLowerCase(Locale.ROOT);
    if (lowerPath.startsWith(SOURCEMAP_PREFIX)) {
      return true;
    }
    for (String extension : ASSET_EXTENSIONS) {
      if (lowerPath.endsWith(extension)) {
        return true;
      }
    }
    return false;
  }
}
