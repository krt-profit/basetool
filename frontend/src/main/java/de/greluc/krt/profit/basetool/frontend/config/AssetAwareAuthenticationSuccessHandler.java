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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * {@link AuthenticationSuccessHandler} that delegates to {@link
 * SavedRequestAwareAuthenticationSuccessHandler} but discards a saved request pointing at a static
 * asset.
 *
 * <p>When the saved URL's path starts with {@code /sm/} or ends in one of {@link
 * #ASSET_EXTENSIONS}, the saved request is removed and the response redirects to {@code /}, so a
 * background asset request never becomes the post-login target.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AssetAwareAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  /**
   * Lower-cased file extensions that mark a saved URL's path, without query string, as a static
   * asset.
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

  /** The request cache used to look up and remove the saved request; never {@code null}. */
  private final @NotNull RequestCache requestCache;

  /**
   * The success handler invoked when the saved request is absent or points to a non-asset URL;
   * never {@code null}.
   */
  private final @NotNull AuthenticationSuccessHandler delegate;

  /**
   * Builds the handler around the given request cache and a {@link
   * SavedRequestAwareAuthenticationSuccessHandler} with default target {@code /} that reads the
   * same cache.
   *
   * @param requestCache the cache the filter chain saves into; never {@code null}
   */
  public AssetAwareAuthenticationSuccessHandler(@NotNull RequestCache requestCache) {
    this(requestCache, defaultDelegate(requestCache));
  }

  /**
   * Builds the wrapped default handler, pointed at the same request cache so the replay reads what
   * the chain saved.
   *
   * @param requestCache the shared cache; never {@code null}
   * @return the delegate whose default target URL is {@code /}
   */
  @NotNull
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
   * Removes an asset-like saved request and redirects to the context root, or otherwise delegates
   * to the wrapped {@link SavedRequestAwareAuthenticationSuccessHandler}.
   *
   * @param request the current request, for the saved request and the context path
   * @param response the response the redirect is written to
   * @param authentication the successful authentication, forwarded to the delegate
   * @throws IOException if writing the redirect or the delegate fails
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
   * Checks whether a URL's path starts with {@link #SOURCEMAP_PREFIX} or ends with one of {@link
   * #ASSET_EXTENSIONS}, ignoring the query string.
   *
   * @param url the saved URL; may be {@code null}
   * @return {@code true} for an asset path; {@code false} otherwise, including {@code null} and
   *     unparsable input
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
