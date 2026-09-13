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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the one list both session gates read, and the distinction between its two halves.
 *
 * <p>The list exists because it used to be two lists. {@code TermsAcceptanceGateFilter} and {@code
 * BackendRoleSyncFilter} each carried a private {@code isStaticAsset} with an identical body, and
 * nothing compared them — so {@code /.well-known/assetlinks.json} could be, and was, {@code
 * permitAll} in {@code SecurityConfig} and exempt in neither gate. A signed-in member who opened
 * the Android App Links descriptor got the consent page, and paid a {@code /api/v1/users/me} round
 * trip for it.
 *
 * <p>What the gates do with the list is asserted in their own tests; this one asserts the list.
 */
@DisplayName("Public paths")
class PublicPathsTest {

  @ParameterizedTest
  @DisplayName("public documents are exempt from both gates")
  @ValueSource(strings = {"/robots.txt", "/.well-known/assetlinks.json", "/manifest.webmanifest"})
  void publicDocumentsAreGateExempt(String path) {
    // Each is a permitAll matcher in SecurityConfig and a row in REQ-SEC-052's public-path table.
    // permitAll only decides authorisation; it does not stop a filter further down the chain from
    // redirecting an authenticated caller, which is what this predicate is for.
    assertThat(PublicPaths.isPublicDocument(path)).as(path).isTrue();
    assertThat(PublicPaths.isAssetOrPublicDocument(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
  }

  @ParameterizedTest
  @DisplayName("the asset trees, the favicon and sourcemaps are exempt from both gates")
  @ValueSource(
      strings = {
        "/css/styles.css",
        "/js/krt-fetch.js",
        "/images/x.png",
        "/logos/basetool-favicon.svg",
        "/fonts/Lato-Regular.woff2",
        "/favicon.ico",
        "/sm/abcdef123456.map",
        "/js/vendor/example-1.0.0.min.js.map"
      })
  void assetsAreGateExempt(String path) {
    assertThat(PublicPaths.isStaticAsset(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
  }

  @ParameterizedTest
  @DisplayName("a member-only page is exempt from nothing")
  @ValueSource(strings = {"/missions", "/bank", "/lager", "/admin/users", "/", "/profile"})
  void memberPagesAreNotExempt(String path) {
    // The gates exist for exactly these, so a predicate that let one through would be the whole
    // defect. "/" is included on purpose: it is permitAll, but it is a PAGE — the consent gate must
    // still be able to redirect a signed-in member away from it.
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
    assertThat(PublicPaths.isAssetOrPublicDocument(path)).as(path).isFalse();
  }

  @ParameterizedTest
  @DisplayName("auth plumbing is gate-exempt but is NOT part of the cheap-skip set")
  @ValueSource(
      strings = {
        "/logout",
        "/oauth2/authorization/keycloak",
        "/login/oauth2/code/keycloak",
        "/error",
        "/actuator/health"
      })
  void authInfrastructureIsExemptButNotSkippable(String path) {
    // The distinction is load-bearing rather than tidy. BackendRoleSyncFilter skips its ENTIRE body
    // for isAssetOrPublicDocument, and the OAuth callback under /login is where a member's
    // authorities are first reconciled — folding the two predicates together would skip the work
    // the filter exists to do, and the symptom would be stale roles after a promotion.
    assertThat(PublicPaths.isAuthInfrastructure(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
    assertThat(PublicPaths.isAssetOrPublicDocument(path)).as(path).isFalse();
  }

  @Test
  @DisplayName("the path is taken relative to the context path, not raw")
  void relativePathStripsTheContextPath() {
    // Every predicate above is written against a leading "/", so a non-root context path would
    // silently exempt nothing if the caller passed the raw URI. This is the line that used to be
    // copy-pasted into three methods across the two filters.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/basetool/robots.txt");
    request.setContextPath("/basetool");
    request.setRequestURI("/basetool/robots.txt");

    assertThat(PublicPaths.relativePath(request)).isEqualTo("/robots.txt");
    assertThat(PublicPaths.isGateExempt(PublicPaths.relativePath(request))).isTrue();
  }

  @Test
  @DisplayName("a path that merely starts with a public document's name is not exempt")
  void doesNotMatchOnAPrefixOfAPublicDocument() {
    // The document entries are exact matches on purpose. A startsWith would hand the exemption to
    // every sibling sharing the opening characters — the lesson the backend sweep recorded in
    // 2026-09.
    assertThat(PublicPaths.isGateExempt("/robots.txt.bak")).isFalse();
    assertThat(PublicPaths.isGateExempt("/manifest.webmanifest/../missions")).isFalse();
    assertThat(PublicPaths.isGateExempt("/.well-known/assetlinks.json.old")).isFalse();
  }

  @ParameterizedTest
  @DisplayName("a .map suffix outside the asset trees buys no exemption")
  @ValueSource(
      strings = {
        "/missions/x.map",
        "/bank/statement.map",
        "/admin/users/1.map",
        "/webjars/some-lib/thing.js.map",
        "/.map"
      })
  void aMapSuffixIsNotAnExemption(String path) {
    // The one case nothing pinned, while the Javadoc argued for it at length.
    //
    // `isStaticAsset` used to end in `|| path.endsWith(".map")`, which fed `isGateExempt` — so a
    // member who had declined the terms, or whose registration was still pending, reached any route
    // with a String path variable by appending `.map`: PathPatternParser binds the suffix into the
    // variable and the controller renders. Both positive cases in `assetsAreGateExempt` pass
    // through a `/sm/` or `/js/` prefix, so every one of them would still pass with the suffix
    // clause restored and nothing here would notice. This is the assertion that fails if it comes
    // back — including under the anchored `^/(css|js)/.*\.map$` pattern that briefly replaced it,
    // which could never decide anything because those two prefixes already matched.
    assertThat(PublicPaths.isStaticAsset(path)).as(path).isFalse();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }
}
