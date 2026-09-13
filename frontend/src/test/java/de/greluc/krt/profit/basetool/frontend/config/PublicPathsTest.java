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
 * <p>The last group of tests pins the second half of that story: the list also has to agree with
 * {@code SecurityConfig} about how a path is <em>spelled</em>. Spring Security matches {@code
 * permitAll} with a {@code PathPatternRequestMatcher}, which decides on the percent-decoded
 * segment, so {@code /.well-known/assetlink%73.json} is {@code permitAll} — and while this class
 * compared the raw URI it was exempt in neither gate. Same defect as above, one layer down.
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
    //
    // These five also pin the "/logout/**"-style patterns against the startsWith prefixes they
    // replaced: "/logout/**" has to match the bare "/logout", or the consent gate would trap a
    // member who declines the terms with no way out.
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

  // ---------------------------------------------------------------------------------------------
  // Percent-encoded spellings.
  //
  // The defect these pin: `relativePath` hands over the RAW `getRequestURI()`, while Spring
  // Security matches its permitAll list with a PathPatternRequestMatcher, which decides on the
  // DECODED segment. Every predicate here therefore matches a parsed PathPattern too, so both
  // layers accept the same spellings. The reference is Spring Security; this class follows it.
  //
  // Two independent layers sit above these cases in production and neither is the reason the
  // predicate is safe: the default StrictHttpFirewall answers 400 for %2e, %2f and %25 (but NOT
  // for %73 or %61), and the gates only ever redirect — they never grant.
  //
  // REQ-SEC-029 governs this, and it named this list as a deliberate exception until 2026-09-13.
  // These cases are the acceptance evidence for the amendment that dropped the exception.
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @DisplayName("an encoded spelling of a public document is exempt, as permitAll already treats it")
  @ValueSource(
      strings = {
        // %73 is 's'. THE case this change fixes, and the one the firewall does not stop:
        // SecurityConfig permitAll-matches it (PathPattern decodes the segment to
        // "assetlinks.json"), so the request is authorised — and while this class compared the raw
        // URI, a gate then redirected it. Allowed by one layer, redirected by the next.
        "/.well-known/assetlink%73.json",
        // Mixed-case hex: %2E is '.'. Proves the decode is hex-case-insensitive, exactly as
        // Spring's is, so the two layers cannot disagree on capitalisation. StrictHttpFirewall
        // refuses %2E with a 400 in production, so this pins the predicate, not a reachable path.
        "/robots%2Etxt",
        // Same shape on the third document, to prove the group is the list rather than one entry.
        "/manifest.webmanife%73t"
      })
  void anEncodedSpellingOfAPublicDocumentIsExempt(String path) {
    assertThat(PublicPaths.isPublicDocument(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
  }

  @Test
  @DisplayName("relativePath stays raw, and the predicate decodes it")
  void relativePathIsRawAndThePredicateDecodesIt() {
    // The end-to-end shape of the fix, and the contract of the two halves: relativePath must NOT
    // decode (a caller that decoded the whole string would re-open the %2F hole below), and the
    // predicate must accept the encoded spelling anyway.
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/basetool/.well-known/assetlink%73.json");
    request.setContextPath("/basetool");
    request.setRequestURI("/basetool/.well-known/assetlink%73.json");

    assertThat(PublicPaths.relativePath(request)).isEqualTo("/.well-known/assetlink%73.json");
    assertThat(PublicPaths.isGateExempt(PublicPaths.relativePath(request))).isTrue();
  }

  @ParameterizedTest
  @DisplayName("an encoded slash cannot manufacture a segment boundary")
  @ValueSource(strings = {"/css%2f../missions", "/js%2F..%2Fadmin/users", "/sm%2f../bank"})
  void anEncodedSlashCannotManufactureAnAssetPrefix(String path) {
    // THE fail-open this change had to avoid, and the reason the predicates match a PathPattern
    // instead of decoding the string and keeping startsWith.
    //
    // Decoding the whole path turns "/css%2f../missions" into "/css/../missions", which passes
    // startsWith("/css/") — a gate exemption for an arbitrary member-only route. PathPattern cannot
    // be fooled that way: a decoded %2F stays INSIDE its segment, so the first segment here is
    // "css/.." and the pattern "/css/**" requires exactly "css". Spring Security refuses these for
    // the same reason, so the two layers still agree — this one just agrees on "no".
    assertThat(PublicPaths.isStaticAsset(path)).as(path).isFalse();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }

  @ParameterizedTest
  @DisplayName("an encoded traversal buys no exemption")
  @ValueSource(
      strings = {
        // %2e%2e is "..". The encoded twin of the "/manifest.webmanifest/../missions" case above:
        // the document patterns are exact, and a three-segment path is not the one-segment
        // document however its segments decode.
        "/%2e%2e/robots.txt",
        "/manifest.webmanifest/%2e%2e/missions",
        "/.well-known/%2e%2e/%2e%2e/admin/users"
      })
  void anEncodedTraversalIsNotExempt(String path) {
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }

  @Test
  @DisplayName("a traversal INSIDE an exempt tree stays exempt, exactly as it was before")
  void anEncodedTraversalInsideAnAssetTreeIsUnchanged() {
    // Written down because it is the one case that looks alarming and is not a regression.
    //
    // "/css/%2e%2e/missions" begins with the segment "css", so "/css/**" matches it — just as the
    // old raw startsWith("/css/") matched it, and just as SecurityConfig's own "/css/**" permitAll
    // matches it. Nothing changed here, and the layers agree. What actually stops the request is
    // the layer above: StrictHttpFirewall rejects %2e outright with a 400, so it never reaches a
    // gate at all. If that firewall setting is ever relaxed, THIS is the assertion to revisit.
    assertThat(PublicPaths.isStaticAsset("/css/%2e%2e/missions")).isTrue();
    assertThat(PublicPaths.isGateExempt("/css/%2e%2e/missions")).isTrue();
  }

  @ParameterizedTest
  @DisplayName("a double-encoded spelling is decoded once, not twice")
  @ValueSource(
      strings = {
        // %252e decodes ONCE to the literal text "%2e" — not to ".". If anything ever decoded a
        // second time, "/robots.txt%252e" would become "/robots.txt." and "/%252e%252e/robots.txt"
        // would become a traversal; both would then be judged against a string no layer of the
        // stack actually routes on. Spring decodes exactly once, so this class must too.
        "/robots.txt%252e",
        "/%252e%252e/robots.txt",
        "/.well-known/assetlinks.json%252e",
        // The other direction: one decode leaves "%73" as literal text, so this is NOT the
        // assetlinks document. The single-encoded twin above IS exempt — that pair is the proof
        // that the decode happens once.
        "/.well-known/assetlink%2573.json"
      })
  void aDoubleEncodedSpellingIsNotExempt(String path) {
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }
}
