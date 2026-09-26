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

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides which paths the frontend's session gates ({@link TermsAcceptanceGateFilter}, {@link
 * BackendRoleSyncFilter}) may not redirect (REQ-SEC-029).
 *
 * <p>Every predicate matches parsed {@link PathPattern}s segment by segment, the same way {@code
 * SecurityConfig}'s {@code permitAll} list is matched, so both layers accept the same spellings.
 */
final class PublicPaths {

  /** Parses the patterns below once; matching is per request and allocation-light. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The static asset trees, the favicon and the sourcemap directory.
   *
   * <p>{@code /sm/**} is listed even though its files end in {@code .map}: the prefix is what
   * {@code SecurityConfig} allow-lists, and a sourcemap served from there without the extension
   * would otherwise fall through.
   */
  private static final List<PathPattern> STATIC_ASSETS =
      List.of(
          PATH_PARSER.parse("/css/**"),
          PATH_PARSER.parse("/js/**"),
          PATH_PARSER.parse("/images/**"),
          PATH_PARSER.parse("/logos/**"),
          PATH_PARSER.parse("/fonts/**"),
          PATH_PARSER.parse("/sm/**"),
          PATH_PARSER.parse("/favicon.ico"));

  /** The public documents, each an exact path — see {@link #isPublicDocument}. */
  private static final List<PathPattern> PUBLIC_DOCUMENTS =
      List.of(
          PATH_PARSER.parse("/robots.txt"),
          PATH_PARSER.parse("/.well-known/assetlinks.json"),
          PATH_PARSER.parse("/manifest.webmanifest"));

  /** The authentication plumbing patterns; see {@link #isAuthInfrastructure}. */
  private static final List<PathPattern> AUTH_INFRASTRUCTURE =
      List.of(
          PATH_PARSER.parse("/logout/**"),
          PATH_PARSER.parse("/oauth2/**"),
          PATH_PARSER.parse("/login/**"),
          PATH_PARSER.parse("/error/**"),
          PATH_PARSER.parse("/actuator/**"));

  /**
   * The four pages a gate may never hold a member away from — see {@link #isLegalPage}. {@code
   * /licenses} joined the three original ones with REQ-UI-021: a third-party licence notice is a
   * legal page too, and a member held at the consent page must still be able to read what the
   * software they are asked to accept terms for is built from.
   */
  private static final List<PathPattern> LEGAL_PAGES =
      List.of(
          PATH_PARSER.parse("/impressum"),
          PATH_PARSER.parse("/privacy"),
          PATH_PARSER.parse("/terms"),
          PATH_PARSER.parse("/licenses"));

  /** Not instantiable: a predicate holder with no state. */
  private PublicPaths() {}

  /**
   * Strips the servlet context path from the request URI.
   *
   * <p>The result is still percent-encoded; pass it to the predicates here and never compare or
   * decode it as a whole string.
   *
   * @param request the current request; never {@code null}
   * @return the context-relative raw path, always starting with {@code /}
   */
  static @NotNull String relativePath(@NotNull HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  /**
   * Checks whether the path is a static asset or a public document, whose response cannot depend on
   * who is asking.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} when no session gate may redirect this path
   */
  static boolean isAssetOrPublicDocument(@NotNull String path) {
    return isAssetOrPublicDocument(PathContainer.parsePath(path));
  }

  /**
   * {@link #isAssetOrPublicDocument(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} when no session gate may redirect this path
   */
  private static boolean isAssetOrPublicDocument(@NotNull PathContainer path) {
    return isStaticAsset(path) || isPublicDocument(path);
  }

  /**
   * Checks whether the path is in one of the static asset trees, or is the favicon or a sourcemap
   * inside them.
   *
   * <p>There is deliberately no bare {@code .map} suffix match, which would let any path bypass the
   * gates.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for an asset path
   */
  static boolean isStaticAsset(@NotNull String path) {
    return isStaticAsset(PathContainer.parsePath(path));
  }

  /**
   * {@link #isStaticAsset(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} for an asset path
   */
  private static boolean isStaticAsset(@NotNull PathContainer path) {
    return matchesAny(STATIC_ASSETS, path);
  }

  /**
   * Checks whether the path is a public document that is {@code permitAll} in {@code
   * SecurityConfig} (REQ-SEC-052).
   *
   * <ul>
   *   <li>{@code /robots.txt} — crawler directives.
   *   <li>{@code /.well-known/assetlinks.json} — Android App Links verification (REQ-SEC-038).
   *   <li>{@code /manifest.webmanifest} — the web app manifest (REQ-UI-020).
   * </ul>
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for a public document
   */
  static boolean isPublicDocument(@NotNull String path) {
    return isPublicDocument(PathContainer.parsePath(path));
  }

  /**
   * {@link #isPublicDocument(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} for a public document
   */
  private static boolean isPublicDocument(@NotNull PathContainer path) {
    return matchesAny(PUBLIC_DOCUMENTS, path);
  }

  /**
   * Checks whether the path belongs to the authentication plumbing a gated user needs: login,
   * logout, OAuth, error page and actuator.
   *
   * <p>Kept separate from {@link #isAssetOrPublicDocument}, because {@code BackendRoleSyncFilter}
   * must still run on the login callback.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for a login, logout, OAuth, error or actuator path
   */
  static boolean isAuthInfrastructure(@NotNull String path) {
    return isAuthInfrastructure(PathContainer.parsePath(path));
  }

  /**
   * {@link #isAuthInfrastructure(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} for a login, logout, OAuth, error or actuator path
   */
  private static boolean isAuthInfrastructure(@NotNull PathContainer path) {
    return matchesAny(AUTH_INFRASTRUCTURE, path);
  }

  /**
   * Checks whether neither session gate may redirect the path: assets, public documents,
   * authentication plumbing and legal pages. Each gate adds its own landing page on top.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} when neither session gate may redirect this path
   */
  static boolean isGateExempt(@NotNull String path) {
    PathContainer parsed = PathContainer.parsePath(path);
    return isAssetOrPublicDocument(parsed) || isAuthInfrastructure(parsed) || isLegalPage(parsed);
  }

  /**
   * Checks whether the path is one of the pages a member must be able to read while a gate holds
   * them: imprint, privacy policy, terms and licence notice (REQ-UI-021).
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for the imprint, the privacy policy, the public terms page or the licence
   *     notice
   */
  static boolean isLegalPage(@NotNull String path) {
    return isLegalPage(PathContainer.parsePath(path));
  }

  /**
   * {@link #isLegalPage(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} for the imprint, the privacy policy, the public terms page or the licence
   *     notice
   */
  private static boolean isLegalPage(@NotNull PathContainer path) {
    return matchesAny(LEGAL_PAGES, path);
  }

  /**
   * Whether any pattern in the group matches the parsed path.
   *
   * @param patterns one of the four groups above
   * @param path the parsed context-relative path
   * @return {@code true} if at least one pattern matches
   */
  private static boolean matchesAny(
      @NotNull List<PathPattern> patterns, @NotNull PathContainer path) {
    for (PathPattern pattern : patterns) {
      if (pattern.matches(path)) {
        return true;
      }
    }
    return false;
  }
}
