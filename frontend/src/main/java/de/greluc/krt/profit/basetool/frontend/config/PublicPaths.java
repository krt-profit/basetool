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
import org.jetbrains.annotations.NotNull;

/**
 * The one place that answers "may a session gate redirect this path?" for the frontend's two
 * session-state filters.
 *
 * <p>It exists because the answer used to be written twice. {@link TermsAcceptanceGateFilter} and
 * {@link BackendRoleSyncFilter} each carried a private {@code isStaticAsset} whose bodies were
 * byte-identical, and each repeated the same five infrastructure prefixes and the same context-path
 * arithmetic. Nothing compared them, so the lists were free to drift — and they had: {@code
 * /.well-known/assetlinks.json} sat in {@code SecurityConfig}'s {@code permitAll} list and in
 * <em>neither</em> filter, so a signed-in member who opened the Android App Links descriptor was
 * answered with the consent page and paid a {@code /api/v1/users/me} round trip for it. Adding a
 * public path meant remembering four files; now it means editing one of the two lists below.
 *
 * <p><strong>Two predicates, because the callers genuinely want different things.</strong> {@link
 * #isAssetOrPublicDocument} is the cheap-skip set: paths whose response cannot depend on session
 * state, which {@code BackendRoleSyncFilter} skips its whole body for. {@link
 * #isAuthInfrastructure} is the set that must stay reachable so a gated user can get <em>out</em> —
 * login, logout, the OAuth endpoints, the error page. The role-sync filter must not skip its body
 * for those (the login callback is where authorities are first reconciled), which is exactly why
 * they are not merged into one predicate.
 *
 * <p><strong>Not in scope, deliberately.</strong> {@code RequestLoggingFilter} keeps its own
 * extension list: it decides what is too noisy to log, which includes {@code /actuator/} and {@code
 * /webjars/} and excludes nothing on session grounds. {@code
 * AssetAwareAuthenticationSuccessHandler} keeps its own too: it matches file <em>extensions</em> on
 * a saved request, a different shape for a different question. Folding either in here would merge
 * two lists that only look alike.
 */
final class PublicPaths {

  /** Not instantiable: a predicate holder with no state. */
  private PublicPaths() {}

  /**
   * Strips the servlet context path, yielding the path the lists below are written against.
   *
   * <p>Spelled once here because it appeared three times across the two filters, and a fourth
   * caller getting it subtly wrong would silently exempt nothing under a non-root context path.
   *
   * @param request the current request; never {@code null}
   * @return the context-relative path, always starting with {@code /}
   */
  static @NotNull String relativePath(@NotNull HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  /**
   * Whether the path is a static asset or a public document — something whose response cannot
   * depend on who is asking.
   *
   * <p>Two groups with one consequence. The <strong>asset trees</strong> are here for cost: the
   * approval and role state refresh on a TTL rather than once per session, so without this every
   * CSS, JS and font request of a page load would be a candidate for a backend read. The
   * <strong>public documents</strong> are here for correctness: each is a {@code permitAll} entry
   * in {@code SecurityConfig} that an external verifier or a browser fetches on its own schedule,
   * and answering one with a consent redirect turns a document into a login page. That is not
   * hypothetical — it is how {@code /.well-known/assetlinks.json} broke the Android login once.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} when no session gate may redirect this path
   */
  static boolean isAssetOrPublicDocument(@NotNull String path) {
    return isStaticAsset(path) || isPublicDocument(path);
  }

  /**
   * Whether the path is one of the static asset trees, the favicon, or a sourcemap.
   *
   * <p>{@code /sm/} is listed even though its files end in {@code .map}: the prefix is what {@code
   * SecurityConfig} allow-lists, and a sourcemap served from there without the extension would
   * otherwise fall through.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for an asset path
   */
  static boolean isStaticAsset(@NotNull String path) {
    return path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.startsWith("/images/")
        || path.startsWith("/logos/")
        || path.startsWith("/fonts/")
        || path.startsWith("/sm/")
        || path.equals("/favicon.ico")
        || path.endsWith(".map");
  }

  /**
   * Whether the path is a public document served by a controller or from {@code static/}.
   *
   * <p>Each entry is a {@code permitAll} matcher in {@code SecurityConfig} and a row in
   * REQ-SEC-052's public-path table; this method is the other half of that declaration, because a
   * path being {@code permitAll} does not stop a later filter in the chain from redirecting an
   * <em>authenticated</em> caller away from it.
   *
   * <ul>
   *   <li>{@code /robots.txt} — crawler directives (M-17).
   *   <li>{@code /.well-known/assetlinks.json} — Android App Links verification, fetched by the
   *       platform with no session at all (REQ-SEC-038).
   *   <li>{@code /manifest.webmanifest} — the web app manifest (REQ-UI-020, ADR-0164). Its {@code
   *       <link>} carries no credentials, so a browser's own fetch never reaches a gate; a member
   *       opening the URL directly would, and would get the consent page.
   * </ul>
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for a public document
   */
  static boolean isPublicDocument(@NotNull String path) {
    return path.equals("/robots.txt")
        || path.equals("/.well-known/assetlinks.json")
        || path.equals("/manifest.webmanifest");
  }

  /**
   * Whether the path belongs to the authentication plumbing a gated user needs to get out.
   *
   * <p>Logout and the OAuth endpoints, or a member who declines the terms is trapped on the consent
   * page; the error page, because an error view that needs a session cannot render the outage that
   * broke it; {@code /actuator}, because the Docker health check must answer while the application
   * is in any state at all.
   *
   * <p><strong>This is not part of {@link #isAssetOrPublicDocument} on purpose.</strong> {@code
   * BackendRoleSyncFilter} skips its entire body for that predicate, and the OAuth callback under
   * {@code /login} is precisely where a member's authorities are first reconciled — folding the two
   * together would skip the reconciliation that the filter exists for.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for a login, logout, OAuth, error or actuator path
   */
  static boolean isAuthInfrastructure(@NotNull String path) {
    return path.startsWith("/logout")
        || path.startsWith("/oauth2")
        || path.startsWith("/login")
        || path.startsWith("/error")
        || path.startsWith("/actuator");
  }

  /**
   * The union both session gates refuse to redirect: assets, public documents, and the
   * authentication plumbing.
   *
   * <p>Each gate adds its own page on top — the consent page for {@link TermsAcceptanceGateFilter},
   * the waiting page for {@link BackendRoleSyncFilter} — because those differ and merging them
   * would let each gate exempt the other's landing page.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} when neither session gate may redirect this path
   */
  static boolean isGateExempt(@NotNull String path) {
    return isAssetOrPublicDocument(path) || isAuthInfrastructure(path);
  }
}
