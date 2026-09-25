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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Tests the properties that decide whether iOS devices can install the Basetool (REQ-UI-020,
 * ADR-0164): the manifest response and the {@code <head>} tags of the rendered landing page.
 *
 * <p>Assertions name whole tags, never a bare attribute.
 */
@SpringBootTest
@DisplayName("Web app manifest")
class WebAppManifestControllerTest {

  /** The registered media type for a manifest; a browser may ignore anything else. */
  private static final String MANIFEST_JSON = "application/manifest+json";

  /** The manifest path, spelled once so a rename cannot half-land. */
  private static final String MANIFEST_PATH = "/manifest.webmanifest";

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
   * Mocks the client registration so the context does not perform OIDC discovery against an
   * unreachable issuer.
   */
  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  /**
   * Reads one CSS custom property from {@link #STYLES_CSS}, expanding three-digit shorthand.
   *
   * @param property the custom-property name, including the leading {@code --}
   * @return the declared colour as lower-case {@code #rrggbb}
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
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MANIFEST_JSON));
  }

  @Test
  @DisplayName("answers every Accept header, because content negotiation must not gate it")
  void answersEveryAcceptHeader() throws Exception {
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
  @DisplayName("an unparseable ?lang= cannot turn the always-200 endpoint into a 500")
  void survivesAnUnparseableLangParameter() throws Exception {
    for (String requested : List.of("!", "de_", "de__DE", "a b")) {
      mockMvc
          .perform(get(MANIFEST_PATH).param("lang", requested))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.lang").value("de"))
          .andExpect(jsonPath("$.name").value("Profit Basetool"));
    }
  }

  @Test
  @DisplayName("is publicly cacheable, because the body is a pure function of its URL")
  void cachedPublicly() throws Exception {
    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(header().string("Cache-Control", "max-age=3600, public"))
        .andExpect(header().doesNotExist("Vary"));
  }

  @Test
  @DisplayName("is linked from the rendered page with the reader's locale in the URL")
  void linkedFromTheRenderedPage() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "<link rel=\"manifest\" href=\"/manifest.webmanifest?locale=de\">")))
        .andExpect(content().string(not(containsString("crossorigin=\"use-credentials\""))));
  }

  @Test
  @DisplayName("keeps both the standard and the legacy iOS standalone hints")
  void keepsBothStandaloneHints() throws Exception {
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
        .andExpect(
            content()
                .string(
                    containsString(
                        "<meta name=\"apple-mobile-web-app-title\" content=\"Basetool\">")));
  }

  @Test
  @DisplayName("agrees with the rendered page AND with the stylesheet about the header fill")
  void themeColourMatchesTheRenderedPageAndTheStylesheet() throws Exception {
    String headerFill = cssColour("--color-bg-dark-gray");
    String pageBackground = cssColour("--color-bg-black");

    mockMvc
        .perform(get("/"))
        .andExpect(
            content()
                .string(
                    containsString("<meta name=\"theme-color\" content=\"" + headerFill + "\">")));

    mockMvc
        .perform(get(MANIFEST_PATH))
        .andExpect(jsonPath("$.theme_color").value(headerFill))
        .andExpect(jsonPath("$.background_color").value(pageBackground));
  }

  @Test
  @DisplayName("registers no service worker anywhere in the frontend")
  void registersNoServiceWorker() throws Exception {
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

  /** The manifest's fallback language equals the default locale of {@code LocaleConfig}. */
  @Test
  void theDefaultLocaleMatchesTheResolvers() throws Exception {
    LocaleResolver resolver = context.getBean(LocaleResolver.class);
    assertInstanceOf(
        CookieLocaleResolver.class,
        resolver,
        "the locale resolver is the cookie one; this test reads its default");

    mockMvc
        .perform(get("/manifest.webmanifest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lang").value("de"));
  }
}
