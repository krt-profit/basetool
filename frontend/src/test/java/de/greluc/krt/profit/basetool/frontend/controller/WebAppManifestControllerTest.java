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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the properties that decide whether an iPhone or iPad can install the Basetool at all
 * (REQ-UI-020, ADR-0164).
 *
 * <p>Every assertion here stands for a failure mode that produces no error anywhere. A manifest
 * that redirects, arrives with the wrong content type, or names the app after the login page breaks
 * no build, no log line and no page — it silently degrades an install that nobody re-tests
 * afterwards. {@link AssetLinksController} beside it exists because exactly that happened once on
 * the Android side, and was found only from a member's account of a broken login.
 *
 * <p>The {@code <head>} half is asserted against the <strong>rendered</strong> landing page rather
 * than against the template source, for the same reason {@code BrandMarkRenderMvcTest} is: only the
 * rendered output proves that the tags survive the fragment include chain and that Thymeleaf
 * actually resolved the message key behind the iOS app title.
 */
@SpringBootTest
@DisplayName("Web app manifest")
class WebAppManifestControllerTest {

  /** The registered media type for a manifest; a browser may ignore anything else. */
  private static final String MANIFEST_JSON = "application/manifest+json";

  /** The manifest path, spelled once so a rename cannot half-land. */
  private static final String MANIFEST_PATH = "/manifest.webmanifest";

  /**
   * Cookie the {@code CookieLocaleResolver} reads.
   *
   * <p>Named here because it is the whole reason the manifest link carries {@code
   * crossorigin="use-credentials"}: without that attribute this cookie never reaches the manifest
   * request and every install is labelled in the default language.
   */
  private static final String LOCALE_COOKIE = "KRT_LOCALE";

  /** The header fill, mirrored by the manifest and by the {@code theme-color} meta tag. */
  private static final String THEME_COLOR = "#141414";

  @Autowired private WebApplicationContext context;

  /**
   * Keeps the backend out of the test.
   *
   * <p>The anonymous landing branch of {@code HomeController} performs no backend call at all, so
   * nothing is stubbed — the mock exists only so the context does not wire a client that would try
   * to reach a real host.
   */
  @MockitoBean private BackendApiClient backendApiClient;

  /**
   * Keeps the real client registration out of the context.
   *
   * <p>Building it performs OIDC discovery against the configured issuer, which no unit test can
   * reach — the context then fails with an {@code UnknownHostException} that says nothing about the
   * endpoint under test. Every other {@code @SpringBootTest} in this module mocks it for the same
   * reason.
   */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  /**
   * Builds a MockMvc that runs the real security filter chain.
   *
   * <p>Without the chain these tests would pass while the endpoint answers {@code 302} into the
   * OAuth entry point in production, which is the single most likely way this feature breaks.
   */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @DisplayName("is served to an anonymous visitor, as a manifest, with no redirect")
  void servedAnonymously() throws Exception {
    // The browser fetches this on the landing page, before any login. None of the three properties
    // holds by default: the path would otherwise fall through to anyRequest().authenticated().
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MANIFEST_JSON));
  }

  @Test
  @DisplayName("declares a standalone app scoped to the whole origin, starting at /")
  void declaresStandaloneApp() throws Exception {
    // start_url "/" is deliberate: it answers with the landing page for a visitor and the dashboard
    // for a member, so one installed icon serves both states. scope "/" keeps in-app navigation out
    // of the browser, and a stable id keeps a later start_url change an update rather than a second
    // installation beside the first.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.display").value("standalone"))
        .andExpect(jsonPath("$.start_url").value("/"))
        .andExpect(jsonPath("$.scope").value("/"))
        .andExpect(jsonPath("$.id").value("/"));
  }

  @Test
  @DisplayName("offers the opaque app icon, and never claims to be maskable")
  void offersTheOpaqueAppIcon() throws Exception {
    // REQ-UI-019 designates this asset for the home screen. "maskable" is the assertion that must
    // NOT appear: Android crops a maskable icon to its own shape and guarantees only the inner
    // ~40 %, so claiming it for artwork drawn without that safe zone cuts into the mark.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.icons[0].src").value("/logos/basetool-appicon-512.png"))
        .andExpect(jsonPath("$.icons[0].sizes").value("512x512"))
        .andExpect(jsonPath("$.icons[0].type").value("image/png"))
        .andExpect(jsonPath("$.icons[0].purpose").value("any"));
  }

  @Test
  @DisplayName("is German by default, because the resolver's default locale is German")
  void germanByDefault() throws Exception {
    // No cookie means German — CookieLocaleResolver ignores Accept-Language once a default locale
    // is set, which is also what a manifest fetched WITHOUT credentials would get. The description
    // is asserted through its umlauts: the bundles store them as ä escapes, and a broken
    // escape surfaces here as mojibake instead of as a build failure.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.lang").value("de"))
        .andExpect(jsonPath("$.name").value("Profit Basetool"))
        .andExpect(jsonPath("$.short_name").value("Basetool"))
        .andExpect(
            jsonPath("$.description")
                .value("Einsätze, Aufträge, Lager und Raffinerie der Organisation."));
  }

  @Test
  @DisplayName("follows the locale cookie, so an English reader installs an English app")
  void followsTheLocaleCookie() throws Exception {
    // This is the test that gives crossorigin="use-credentials" its purpose. If the manifest ever
    // stops reading the cookie, this fails and the attribute's comment stops being a claim nobody
    // checks.
    mockMvc
        .perform(get(MANIFEST_PATH).cookie(new Cookie(LOCALE_COOKIE, "en")))
        .andExpect(jsonPath("$.lang").value("en"))
        .andExpect(jsonPath("$.name").value("Profit Basetool"))
        .andExpect(
            jsonPath("$.description")
                .value("The organisation's missions, orders, inventory and refinery."));
  }

  @Test
  @DisplayName("is cached privately and varies by cookie, because the body is localised")
  void cachedPrivatelyAndVariesByCookie() throws Exception {
    // A shared cache keyed without the cookie would hand a German manifest to a member who reads
    // the tool in English. `private` forbids that cache; `Vary: Cookie` states the dependency for
    // the caches that do store it.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(header().string("Cache-Control", "private, max-age=3600"))
        .andExpect(header().string("Vary", "Cookie"));
  }

  @Test
  @DisplayName("is linked from the rendered page with credentials, or it is never localised")
  void linkedFromTheRenderedPageWithCredentials() throws Exception {
    // "/" is permitAll, so the anonymous landing page is the earliest surface a visitor sees and
    // the one a browser reads the manifest link from. crossorigin="use-credentials" is easy to drop
    // as "unnecessary on a same-origin URL", which is exactly why it is pinned to the rendered
    // output rather than explained in a comment alone.
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("rel=\"manifest\"")))
        .andExpect(content().string(containsString("crossorigin=\"use-credentials\"")))
        .andExpect(content().string(containsString(MANIFEST_PATH)));
  }

  @Test
  @DisplayName("keeps both the standard and the legacy iOS standalone hints")
  void keepsBothStandaloneHints() throws Exception {
    // iOS still honours the prefixed spelling; dropping it downgrades older iPhones from a
    // standalone window back to a Safari tab, with no warning anywhere. The status bar stays
    // `black` rather than `black-translucent`, because translucent draws the page under the status
    // bar and needs safe-area padding the layout does not have.
    mockMvc
        .perform(get("/"))
        .andExpect(content().string(containsString("name=\"mobile-web-app-capable\"")))
        .andExpect(content().string(containsString("name=\"apple-mobile-web-app-capable\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black\">")))
        // The iOS home-screen label comes from the bundle, so this also proves Thymeleaf resolved
        // the key rather than rendering it literally.
        .andExpect(
            content()
                .string(
                    containsString(
                        "<meta name=\"apple-mobile-web-app-title\" content=\"Basetool\">")));
  }

  @Test
  @DisplayName("agrees with the rendered theme-color about the header fill")
  void themeColourMatchesTheRenderedPage() throws Exception {
    // Two places state the same colour and neither can read the other: a <meta> tag cannot resolve
    // a CSS custom property, and the controller cannot read the template. Without this pairing the
    // two drift the first time one is adjusted, and the symptom — a phone status bar one shade off
    // the header it sits above — is invisible in review and on every desktop browser.
    mockMvc
        .perform(get("/"))
        .andExpect(
            content()
                .string(
                    containsString("<meta name=\"theme-color\" content=\"" + THEME_COLOR + "\">")));

    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.theme_color").value(THEME_COLOR))
        .andExpect(jsonPath("$.background_color").value("#000000"));
  }
}
