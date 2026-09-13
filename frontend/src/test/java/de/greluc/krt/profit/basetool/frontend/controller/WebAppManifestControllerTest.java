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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 *
 * <p><strong>Assert whole tags, never a bare attribute.</strong> An earlier revision checked for
 * the substring {@code crossorigin="use-credentials"} anywhere in the response; the rationale
 * comment above the tag contained those very characters and was emitted into the page, so deleting
 * the attribute from the {@code <link>} kept the suite green. Any assertion here that names an
 * attribute names the element it belongs to as well.
 */
@SpringBootTest
@DisplayName("Web app manifest")
class WebAppManifestControllerTest {

  /** The registered media type for a manifest; a browser may ignore anything else. */
  private static final String MANIFEST_JSON = "application/manifest+json";

  /** The manifest path, spelled once so a rename cannot half-land. */
  private static final String MANIFEST_PATH = "/manifest.webmanifest";

  /** The header fill, mirrored by the manifest, the {@code theme-color} meta tag and the CSS. */
  private static final String THEME_COLOR = "#141414";

  /** The page background, mirrored by the manifest's {@code background_color} and the CSS. */
  private static final String BACKGROUND_COLOR = "#000000";

  /** Main resources of this module, the root every source-reading assertion below resolves from. */
  private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

  /** The stylesheet holding the design system's custom properties (REQ-UI-003). */
  private static final Path STYLES_CSS = MAIN_RESOURCES.resolve("static/css/styles.css");

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
   * Reads one CSS custom property out of {@link #STYLES_CSS} and normalises it to six-digit hex.
   *
   * <p>Three-digit shorthand is expanded, because {@code --color-bg-black} is written {@code #000}
   * while a manifest member has to be a full {@code #000000} — comparing the two literally would
   * fail on a difference that does not exist.
   *
   * @param property the custom-property name, including the leading {@code --}
   * @return the declared colour as {@code #rrggbb}, lower-case
   * @throws IOException when the stylesheet cannot be read
   */
  private static String cssColour(String property) throws IOException {
    String css = Files.readString(STYLES_CSS, StandardCharsets.UTF_8);
    Matcher match =
        Pattern.compile(Pattern.quote(property) + "\\s*:\\s*(#[0-9a-fA-F]{3,6})\\s*;").matcher(css);
    assertThat(match.find()).as("%s is declared in %s", property, STYLES_CSS).isTrue();
    String value = match.group(1).toLowerCase(Locale.ROOT);
    if (value.length() == 4) {
      value =
          "#"
              + value.charAt(1)
              + value.charAt(1)
              + value.charAt(2)
              + value.charAt(2)
              + value.charAt(3)
              + value.charAt(3);
    }
    return value;
  }

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
  @DisplayName("answers every Accept header, because content negotiation must not gate it")
  void answersEveryAcceptHeader() throws Exception {
    // A `produces` on the mapping made this a 500: application/json is NOT compatible with
    // application/manifest+json, so a caller asking for JSON got
    // HttpMediaTypeNotAcceptableException,
    // which the Exception catch-all in GlobalExceptionHandler renders as an error page and logs at
    // ERROR. krtFetch sends exactly that Accept, and so does a blackbox probe with a header set.
    for (MediaType accept :
        List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML, MediaType.ALL)) {
      mockMvc
          .perform(get(MANIFEST_PATH).accept(accept))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MANIFEST_JSON))
          .andExpect(jsonPath("$.display").value("standalone"));
    }
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
  @DisplayName("offers exactly one opaque, content-hashed app icon, and never claims maskable")
  void offersTheOpaqueAppIcon() throws Exception {
    // REQ-UI-019 designates this asset for the home screen. Three separate properties:
    //
    // 1. The URL is FINGERPRINTED. /logos/** is served `immutable` for a year, so a manifest naming
    //    the bare path would pin every installed home screen to a URL no browser revalidates — a
    //    redesigned icon would never arrive.
    // 2. There is exactly ONE entry. Asserting only icons[0] would let a second, maskable entry
    //    through, which is the thing the spec says must never appear.
    // 3. purpose is "any". Android crops a maskable icon to its own shape and guarantees only the
    //    inner ~40 %, so claiming it for artwork drawn without that safe zone cuts into the mark.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.icons.length()").value(1))
        .andExpect(
            jsonPath("$.icons[0].src")
                .value(matchesPattern("/logos/basetool-appicon-512-[0-9a-f]+\\.png")))
        .andExpect(jsonPath("$.icons[0].sizes").value("512x512"))
        .andExpect(jsonPath("$.icons[0].type").value("image/png"))
        .andExpect(jsonPath("$.icons[0].purpose").value("any"));
  }

  @Test
  @DisplayName("is German when the URL names no locale, because German is the app's default")
  void germanByDefault() throws Exception {
    // No ?locale= means German — the application's default, not the platform's. The description is
    // asserted through its umlauts: the bundles store those as backslash-u Unicode escapes and
    // never as the literal character, so a broken escape surfaces here as mojibake rather than
    // as a build failure. The escape cannot be spelled out even in this comment: javac resolves
    // those sequences BEFORE tokenising, so an invalid one inside a comment is still a compile
    // error -- which is exactly what an earlier attempt at this sentence produced.
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
  @DisplayName("follows ?locale=, so an English reader installs an English-described app")
  void followsTheLocaleQueryParameter() throws Exception {
    // The locale travels in the URL rather than in a cookie, which is what keeps the fetch
    // anonymous. Note what does NOT change: name and short_name are identical in both bundles
    // (the product name is a proper noun), so `description` and `lang` are the only members a
    // locale can move — see ADR-0164.
    mockMvc
        .perform(get(MANIFEST_PATH).param("locale", "en"))
        .andExpect(jsonPath("$.lang").value("en"))
        .andExpect(jsonPath("$.name").value("Profit Basetool"))
        .andExpect(
            jsonPath("$.description")
                .value("The organisation's missions, orders, inventory and refinery."));
  }

  @Test
  @DisplayName("never declares a language it cannot render, whatever the URL asks for")
  void clampsUnsupportedLocales() throws Exception {
    // `fallback-to-system-locale` is false and the base bundle holds GERMAN copy, so passing the
    // requested tag straight through produced a manifest that declared `"lang": "fr"` over German
    // text. A region-qualified tag must still resolve to its language, and a malformed one must not
    // produce an empty `lang`, which the specification does not permit.
    for (String requested : List.of("fr", "he", "xx", "_DE", "")) {
      mockMvc
          .perform(get(MANIFEST_PATH).param("locale", requested))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.lang").value("de"))
          .andExpect(
              jsonPath("$.description")
                  .value("Einsätze, Aufträge, Lager und Raffinerie der Organisation."));
    }
    mockMvc
        .perform(get(MANIFEST_PATH).param("locale", "en-GB"))
        .andExpect(jsonPath("$.lang").value("en"))
        .andExpect(
            jsonPath("$.description")
                .value("The organisation's missions, orders, inventory and refinery."));
  }

  @Test
  @DisplayName("is publicly cacheable, because the body is a pure function of its URL")
  void cachedPublicly() throws Exception {
    // The locale is in the URL and nothing is read from the session, so there is no cookie to vary
    // on. An earlier revision sent `private` + `Vary: Cookie`; `private` already forbids a shared
    // cache, and `Vary: Cookie` keyed the browser's own cache on the whole Cookie header, so every
    // SESSION rotation threw the entry away.
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(header().string("Cache-Control", "max-age=3600, public"))
        .andExpect(header().doesNotExist("Vary"));
  }

  @Test
  @DisplayName("is linked from the rendered page with the reader's locale in the URL")
  void linkedFromTheRenderedPage() throws Exception {
    // "/" is permitAll, so the anonymous landing page is the earliest surface a visitor sees and
    // the one a browser reads the manifest link from. The WHOLE tag is asserted: a bare-attribute
    // substring check was satisfied by the rationale comment above it, which ships in the response.
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "<link rel=\"manifest\" href=\"/manifest.webmanifest?locale=de\">")))
        // The attribute that is gone is as load-bearing as the one that is there: it made every
        // fetch authenticated, for a document that needs no session.
        .andExpect(content().string(not(containsString("crossorigin=\"use-credentials\""))));
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
        .andExpect(
            content()
                .string(containsString("<meta name=\"mobile-web-app-capable\" content=\"yes\">")))
        .andExpect(
            content()
                .string(
                    containsString("<meta name=\"apple-mobile-web-app-capable\" content=\"yes\">")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "<meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black\">")))
        // The iOS home-screen label comes from the bundle, so this also proves Thymeleaf resolved
        // the key rather than rendering it literally. It is the same in both locales on purpose —
        // REQ-UI-020 no longer claims otherwise.
        .andExpect(
            content()
                .string(
                    containsString(
                        "<meta name=\"apple-mobile-web-app-title\" content=\"Basetool\">")));
  }

  @Test
  @DisplayName("agrees with the rendered page AND with the stylesheet about the header fill")
  void themeColourMatchesTheRenderedPageAndTheStylesheet() throws Exception {
    // Three places state the same colour and none can read the others: a <meta> tag cannot resolve
    // a CSS custom property, the controller cannot read the template, and neither reads the
    // stylesheet. The stylesheet is the one that matters — it is what actually paints the header —
    // and it used to be the one nothing checked, so a design refresh could move it while all the
    // tests stayed green. The symptom is a phone status bar one shade off the header it sits above:
    // invisible in review and on every desktop browser.
    mockMvc
        .perform(get("/"))
        .andExpect(
            content()
                .string(
                    containsString("<meta name=\"theme-color\" content=\"" + THEME_COLOR + "\">")));

    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.theme_color").value(THEME_COLOR))
        .andExpect(jsonPath("$.background_color").value(BACKGROUND_COLOR));

    assertThat(cssColour("--color-bg-dark-gray"))
        .as("theme-color must be the header fill; see REQ-UI-020")
        .isEqualTo(THEME_COLOR);
    assertThat(cssColour("--color-bg-black"))
        .as("background_color must be the page background; see REQ-UI-020")
        .isEqualTo(BACKGROUND_COLOR);
  }

  @Test
  @DisplayName("registers no service worker anywhere in the frontend")
  void registersNoServiceWorker() throws Exception {
    // REQ-UI-020 lists this as an acceptance criterion and ADR-0164's whole privacy argument rests
    // on it, but nothing enforced it: a worker caching navigations would copy balances, rosters and
    // stock into a store outside every path that clears the session, against REQ-SEC-031. Adding
    // one is an ADR, not a refactor — and this is what makes that true rather than aspirational.
    Pattern worker =
        Pattern.compile(
            "serviceWorker|service-worker|navigator\\.serviceworker", Pattern.CASE_INSENSITIVE);
    List<String> offenders = new ArrayList<>();

    try (Stream<Path> files = Files.walk(MAIN_RESOURCES)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".js") && !name.endsWith(".html")) {
          continue;
        }
        if (worker.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
          offenders.add(file.toString());
        }
      }
    }

    assertThat(offenders)
        .as(
            "REQ-UI-020 / ADR-0164: the frontend registers no service worker. Adding one needs an"
                + " ADR, because it would put member data in a store no logout clears.")
        .isEmpty();
  }
}
