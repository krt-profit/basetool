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
 * Unit tests for {@code PublicPaths}: the path list both session gates read, the distinction
 * between its two halves, and its agreement with {@code SecurityConfig} on percent-encoded
 * spellings.
 */
@DisplayName("Public paths")
class PublicPathsTest {

  @ParameterizedTest
  @DisplayName("public documents are exempt from both gates")
  @ValueSource(strings = {"/robots.txt", "/.well-known/assetlinks.json", "/manifest.webmanifest"})
  void publicDocumentsAreGateExempt(String path) {
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
    assertThat(PublicPaths.isAuthInfrastructure(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
    assertThat(PublicPaths.isAssetOrPublicDocument(path)).as(path).isFalse();
  }

  @ParameterizedTest
  @DisplayName("the four legal pages are gate-exempt but are pages, not cheap-skip documents")
  @ValueSource(strings = {"/impressum", "/privacy", "/terms", "/licenses"})
  void legalPagesAreExemptButNotSkippable(String path) {
    assertThat(PublicPaths.isLegalPage(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
    assertThat(PublicPaths.isAssetOrPublicDocument(path)).as(path).isFalse();
  }

  @Test
  @DisplayName("the path is taken relative to the context path, not raw")
  void relativePathStripsTheContextPath() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/basetool/robots.txt");
    request.setContextPath("/basetool");
    request.setRequestURI("/basetool/robots.txt");

    assertThat(PublicPaths.relativePath(request)).isEqualTo("/robots.txt");
    assertThat(PublicPaths.isGateExempt(PublicPaths.relativePath(request))).isTrue();
  }

  @Test
  @DisplayName("a path that merely starts with a public document's name is not exempt")
  void doesNotMatchOnAPrefixOfAPublicDocument() {
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
    assertThat(PublicPaths.isStaticAsset(path)).as(path).isFalse();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }

  @ParameterizedTest
  @DisplayName("an encoded spelling of a public document is exempt, as permitAll already treats it")
  @ValueSource(
      strings = {"/.well-known/assetlink%73.json", "/robots%2Etxt", "/manifest.webmanife%73t"})
  void anEncodedSpellingOfAPublicDocumentIsExempt(String path) {
    assertThat(PublicPaths.isPublicDocument(path)).as(path).isTrue();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isTrue();
  }

  @Test
  @DisplayName("relativePath stays raw, and the predicate decodes it")
  void relativePathIsRawAndThePredicateDecodesIt() {
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
    assertThat(PublicPaths.isStaticAsset(path)).as(path).isFalse();
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }

  @ParameterizedTest
  @DisplayName("an encoded traversal buys no exemption")
  @ValueSource(
      strings = {
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
    assertThat(PublicPaths.isStaticAsset("/css/%2e%2e/missions")).isTrue();
    assertThat(PublicPaths.isGateExempt("/css/%2e%2e/missions")).isTrue();
  }

  @ParameterizedTest
  @DisplayName("a double-encoded spelling is decoded once, not twice")
  @ValueSource(
      strings = {
        "/robots.txt%252e",
        "/%252e%252e/robots.txt",
        "/.well-known/assetlinks.json%252e",
        "/.well-known/assetlink%2573.json"
      })
  void aDoubleEncodedSpellingIsNotExempt(String path) {
    assertThat(PublicPaths.isGateExempt(path)).as(path).isFalse();
  }
}
