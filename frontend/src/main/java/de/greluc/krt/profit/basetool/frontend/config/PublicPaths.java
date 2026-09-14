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
 * The one place that answers "may a session gate redirect this path?" for the frontend's two
 * session-state filters.
 *
 * <p>It exists because the answer used to be written twice. {@link TermsAcceptanceGateFilter} and
 * {@link BackendRoleSyncFilter} each carried a private {@code isStaticAsset} whose bodies were
 * byte-identical, and each repeated the same five infrastructure prefixes and the same context-path
 * arithmetic. Nothing compared them, so the lists were free to drift — and they had: {@code
 * /.well-known/assetlinks.json} sat in the {@code permitAll} list of {@code SecurityConfig} and in
 * <em>neither</em> filter, so a signed-in member who opened the Android App Links descriptor was
 * answered with the consent page and paid a {@code /api/v1/users/me} round trip for it. Adding a
 * public path meant remembering four files; now it means editing one of the two lists below.
 *
 * <p><strong>Spring Security is the reference, and this class follows it.</strong> Every predicate
 * here matches a parsed {@link PathPattern}, because that is how the {@code permitAll} list in
 * {@code SecurityConfig} is matched: Spring Security 7 resolves {@code requestMatchers(String)} to
 * a {@code PathPatternRequestMatcher}. {@link PathPattern} decides on {@code
 * PathSegment#valueToMatch()}, which is percent-<em>decoded</em>, decoded exactly once, and never
 * across a segment boundary. Matching the same way is what makes the two layers agree.
 *
 * <p>They did not agree before. {@link #relativePath} hands over the raw {@code getRequestURI()},
 * and a raw {@code equals} answers "no" for {@code /.well-known/assetlink%73.json} while {@code
 * SecurityConfig} answers "yes" — so the security layer admitted the request and a session gate
 * then redirected it. That was fail-closed (an encoded spelling cost a redirect; it never granted
 * anything) and therefore a consistency defect rather than a hole, but it is the same defect as the
 * drifted copies above, one layer down: this class is meant to be the single answer, and it was not
 * the single answer for encoded spellings. The backend reached the same conclusion first — its
 * {@code TermsAcceptanceAccessFilter}, {@code PendingApprovalAccessFilter} and {@code
 * RequestBodySizeLimitFilter} all match {@link PathPattern}s rather than raw prefixes, and say why
 * in their own Javadoc.
 *
 * <p>REQ-SEC-029 is the requirement, and it used to carve this list out: an exemption matched raw
 * fails <em>closed</em>, so decoding one only widens it. That carve-out was dropped on 2026-09-13,
 * because the widening is bounded to spellings {@code SecurityConfig} already admits for the same
 * resource, while the disagreement it preserved was unbounded in the number of places two layers
 * could drift apart. The amendment is recorded under REQ-SEC-029 itself.
 *
 * <p><strong>The invariant: decoding widens the spellings, never the set of exempt
 * resources.</strong> {@code /robots%2Etxt} is gate-exempt now where it was not before — but it is
 * the same file, already {@code permitAll} under that spelling, and its bytes do not depend on who
 * is asking. Nothing reaches the exempt set that {@code SecurityConfig} does not already admit
 * under the same spelling; what changed is that both layers recognise the same set of spellings.
 *
 * <p><strong>Why segment-wise matching and not a decoded string.</strong> Decoding the whole path
 * and keeping {@code startsWith} would have been fail-<em>open</em>: {@code /css%2f../missions}
 * decodes to {@code /css/../missions}, which passes {@code startsWith("/css/")} and would have
 * bought a gate exemption Spring Security never grants — {@code /css/**} sees a first segment of
 * {@code css/..}, not {@code css}, because a decoded {@code %2F} stays inside its segment. Matching
 * a {@link PathPattern} cannot make that mistake, because it never re-joins the decoded segments.
 * (The default {@code StrictHttpFirewall} rejects {@code %2e}, {@code %2f} and {@code %25} outright
 * and would refuse such a request with a 400 long before this class saw it. That is a second layer,
 * not the reason this one is safe.) {@code ServletRequestPathUtils} is not the answer either:
 * {@code PathContainer.Element#value()} is contractually the unmodified original.
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

  /**
   * The authentication plumbing — see {@link #isAuthInfrastructure}.
   *
   * <p>These were {@code startsWith} prefixes, which also matched {@code /logoutFoo} and {@code
   * /errorpage}. A {@code /logout/**} pattern matches the {@code /logout} tree and nothing that
   * merely begins with those characters, so the exemption narrowed. That direction is fail-closed
   * and none of the lost spellings is a real endpoint; the real ones — {@code /logout}, {@code
   * /oauth2/authorization/keycloak}, {@code /login/oauth2/code/keycloak}, {@code /error} and {@code
   * /actuator/health} — are pinned in {@code PublicPathsTest}.
   */
  private static final List<PathPattern> AUTH_INFRASTRUCTURE =
      List.of(
          PATH_PARSER.parse("/logout/**"),
          PATH_PARSER.parse("/oauth2/**"),
          PATH_PARSER.parse("/login/**"),
          PATH_PARSER.parse("/error/**"),
          PATH_PARSER.parse("/actuator/**"));

  /** The three pages a gate may never hold a member away from — see {@link #isLegalPage}. */
  private static final List<PathPattern> LEGAL_PAGES =
      List.of(
          PATH_PARSER.parse("/impressum"),
          PATH_PARSER.parse("/privacy"),
          PATH_PARSER.parse("/terms"));

  /** Not instantiable: a predicate holder with no state. */
  private PublicPaths() {}

  /**
   * Strips the servlet context path, yielding the path the predicates below are written against.
   *
   * <p>Spelled once here because it appeared three times across the two filters, and a fourth
   * caller getting it subtly wrong would silently exempt nothing under a non-root context path.
   *
   * <p><strong>The returned path is RAW — still percent-encoded — and that is the
   * contract.</strong> Decoding is the job of the predicates, and they do it the way Spring
   * Security does: per segment, via {@link PathPattern}, exactly once. Do not hand this value to
   * {@code equals} or {@code startsWith} against a literal path, and do not pre-decode it before
   * passing it on. A caller who decodes the whole string first re-introduces the {@code %2F} hole
   * the class comment describes — {@code /css%2f../missions} becomes {@code /css/../missions} and
   * looks like an asset. Only the context-path prefix is treated as raw text here, and that is safe
   * because the context path is configuration rather than something the caller can spell
   * differently.
   *
   * @param request the current request; never {@code null}
   * @return the context-relative path, raw and still percent-encoded, always starting with {@code
   *     /}
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
   * Whether the path is one of the static asset trees, the favicon, or a sourcemap.
   *
   * <p><b>There is deliberately no {@code .map} suffix clause.</b> This predicate feeds {@link
   * #isGateExempt}, so a suffix match let ANY path opt out of both session gates by carrying that
   * ending — a member who declined the terms, or whose registration is still pending, reaches any
   * String-path-variable route by appending {@code .map}, since {@code PathPatternParser} happily
   * binds it into the variable. {@code RequestLoggingFilter} refuses the same shortcut for the same
   * reason, where it costs only an access-log line.
   *
   * <p><b>What the prefix list therefore costs:</b> a sourcemap served from outside {@code /css/},
   * {@code /js/} and {@code /sm/} is not gate-exempt, while {@code SecurityConfig} still {@code
   * permitAll}s {@code /**}{@code /*.map} — so such a path is reachable unauthenticated but
   * redirects a member whose gate is open. Every sourcemap this app serves is built into one of the
   * three trees ({@code frontend/build.gradle.kts} emits them there and nothing on the classpath
   * contributes {@code META-INF/resources}), so the set is empty today; it is written down because
   * a future bundler output directory would fall outside it silently. An anchored {@code
   * ^/(css|js)/.*\.map$} pattern used to sit in this chain and was removed rather than kept: every
   * string it can match already satisfies one of the first two prefixes, {@code ||} short-circuits,
   * and a clause that can never be the deciding one reads like protection while providing none.
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
   * Whether the path is a public document served by a controller or from {@code static/}.
   *
   * <p>Each entry is a {@code permitAll} matcher in {@code SecurityConfig} and a row in
   * REQ-SEC-052's public-path table; this method is the other half of that declaration, because a
   * path being {@code permitAll} does not stop a later filter in the chain from redirecting an
   * <em>authenticated</em> caller away from it. Both halves match the same way, so both accept the
   * same spellings — see the class comment.
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
   * The union both session gates refuse to redirect: assets, public documents, the authentication
   * plumbing and the legal pages.
   *
   * <p>Each gate adds its own page on top — the consent page for {@link TermsAcceptanceGateFilter},
   * the waiting page for {@link BackendRoleSyncFilter} — because those differ and merging them
   * would let each gate exempt the other's landing page.
   *
   * <p>Parses the path once and hands the parsed form to all four groups, because this is the
   * method both filters call on every request.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} when neither session gate may redirect this path
   */
  static boolean isGateExempt(@NotNull String path) {
    PathContainer parsed = PathContainer.parsePath(path);
    return isAssetOrPublicDocument(parsed) || isAuthInfrastructure(parsed) || isLegalPage(parsed);
  }

  /**
   * The three pages a member must be able to read while a gate is holding them.
   *
   * <p>Neither gate may redirect these, and for the same reason in both cases: nobody can be asked
   * to agree to terms they are prevented from reading, and nobody may be cut off from the imprint
   * or the privacy policy because their registration has not been approved yet. {@link
   * TermsAcceptanceGateFilter} said so in its own Javadoc — "that last one is what a careless
   * allowlist drops, and dropping it makes the gate legally self-defeating" — and listed them
   * inline while {@link BackendRoleSyncFilter} did not, so a signed-in member whose registration
   * was PENDING or REJECTED was bounced to the waiting page from all three and had to sign out to
   * read them. They live here now because this class exists precisely so that two gates cannot
   * disagree about what is reachable.
   *
   * @param path a context-relative path, as returned by {@link #relativePath}
   * @return {@code true} for the imprint, the privacy policy or the public terms page
   */
  static boolean isLegalPage(@NotNull String path) {
    return isLegalPage(PathContainer.parsePath(path));
  }

  /**
   * {@link #isLegalPage(String)} against an already-parsed path.
   *
   * @param path the parsed context-relative path
   * @return {@code true} for the imprint, the privacy policy or the public terms page
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
