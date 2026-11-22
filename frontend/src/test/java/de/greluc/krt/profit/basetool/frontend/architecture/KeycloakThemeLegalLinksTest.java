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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every page the Basetool login theme renders itself links the Impressum and the privacy policy
 * through the one {@code krt-legal.ftl} macro (REQ-UI-040).
 */
class KeycloakThemeLegalLinksTest {

  private static final String LOGIN_THEME = "keycloak-theme/krt-theme/login";

  private static final String MACRO = "krt-legal.ftl";

  /** The theme's own page templates at the time of writing; the scan must find at least these. */
  private static final int KNOWN_PAGE_COUNT = 8;

  /**
   * Finds every page template of the login theme that does not import and call the legal-links
   * macro.
   *
   * @throws IOException if the theme directory cannot be read
   */
  @Test
  void everyThemePageRendersTheLegalLinks() throws IOException {
    List<Path> pages;
    try (Stream<Path> files = Files.list(RepositoryPaths.resolve(LOGIN_THEME))) {
      pages =
          files
              .filter(file -> file.getFileName().toString().endsWith(".ftl"))
              .filter(file -> !file.getFileName().toString().equals(MACRO))
              .toList();
    }
    List<String> missing =
        pages.stream().filter(page -> !rendersLegalLinks(page)).map(Path::toString).toList();

    assertThat(pages).hasSizeGreaterThanOrEqualTo(KNOWN_PAGE_COUNT);
    assertThat(missing).as("login pages without the legal links").isEmpty();
  }

  /**
   * The macro links both pages below the theme's home URL, which ends with a slash so the joined
   * paths stay valid.
   *
   * @throws IOException if the macro or the theme properties cannot be read
   */
  @Test
  void theMacroLinksTheImpressumAndThePrivacyPolicyBelowTheHomeUrl() throws IOException {
    String macro =
        Files.readString(
            RepositoryPaths.resolve(LOGIN_THEME + "/" + MACRO), StandardCharsets.UTF_8);
    Properties theme = load(LOGIN_THEME + "/theme.properties");

    assertThat(macro).contains("${krtHome}impressum").contains("${krtHome}privacy");
    assertThat(theme.getProperty("krtHomeUrl")).endsWith("/");
  }

  /**
   * Both theme bundles carry the macro's labels.
   *
   * @param language the bundle's language suffix
   * @throws IOException if the bundle cannot be read
   */
  @ParameterizedTest
  @ValueSource(strings = {"de", "en"})
  void bothBundlesCarryTheLabels(String language) throws IOException {
    Properties messages = load(LOGIN_THEME + "/messages/messages_" + language + ".properties");

    assertThat(messages.stringPropertyNames())
        .contains("krtLegalLinksLabel", "krtLegalImpressum", "krtLegalPrivacy");
  }

  /**
   * Tells whether a page template imports the legal-links macro and calls it.
   *
   * @param page the page template
   * @return {@code true} if the page renders the legal links
   */
  private static boolean rendersLegalLinks(@NotNull Path page) {
    try {
      String source = Files.readString(page, StandardCharsets.UTF_8);
      return source.contains("<#import \"" + MACRO + "\" as legal>")
          && source.contains("<@legal.links/>");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + page, e);
    }
  }

  /**
   * Loads a repository-relative properties file as UTF-8.
   *
   * @param repositoryRelative the path relative to the repository root
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  private static @NotNull Properties load(@NotNull String repositoryRelative) throws IOException {
    Properties properties = new Properties();
    try (Reader reader =
        Files.newBufferedReader(
            RepositoryPaths.resolve(repositoryRelative), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
