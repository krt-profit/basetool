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

package de.greluc.krt.profit.basetool.frontend.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The link catalogue behind the „Star-Citizen-Links" page (REQ-UI-025): every entry has an HTTPS
 * address, a shipped logo and its copy in every bundle, and no logo ships unused.
 */
class ScLinkCatalogTest {

  private static final Path ICON_DIR = Path.of("src/main/resources/static/images/sc-links");

  @Test
  void keysAndAddressesAreUnique() {
    assertThat(Arrays.stream(ScLink.values()).map(ScLink::getKey)).doesNotHaveDuplicates();
    assertThat(Arrays.stream(ScLink.values()).map(ScLink::getUrl)).doesNotHaveDuplicates();
  }

  @Test
  void everyAddressIsAbsoluteHttps() {
    for (ScLink link : ScLink.values()) {
      URI uri = URI.create(link.getUrl());
      assertThat(uri.getScheme()).as(link.name()).isEqualTo("https");
      assertThat(uri.getHost()).as(link.name()).isNotBlank();
    }
  }

  @Test
  void theHostDropsALeadingWww() {
    assertThat(ScLink.SPVIEWER.displayHost()).isEqualTo("spviewer.eu");
    assertThat(ScLink.VERSEKIT.displayHost()).isEqualTo("xharig.github.io");
  }

  @Test
  void everyCategoryHoldsAtLeastOneLink() {
    for (ScLinkCategory category : ScLinkCategory.values()) {
      assertThat(category.links()).as(category.name()).isNotEmpty();
    }
    assertThat(
            Arrays.stream(ScLinkCategory.values())
                .mapToInt(category -> category.links().size())
                .sum())
        .isEqualTo(ScLink.values().length);
  }

  @Test
  void everyLogoShipsAndNoneIsUnused() throws IOException {
    Set<String> referenced =
        Arrays.stream(ScLink.values()).map(ScLink::getIcon).collect(Collectors.toSet());
    try (Stream<Path> files = Files.list(ICON_DIR)) {
      Set<String> shipped =
          files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
      assertThat(shipped).isEqualTo(referenced);
    }
  }

  @Test
  void noSvgLogoCarriesScriptOrExternalReferences() throws IOException {
    for (ScLink link : ScLink.values()) {
      if (!link.getIcon().endsWith(".svg")) {
        continue;
      }
      String svg = Files.readString(ICON_DIR.resolve(link.getIcon()), StandardCharsets.UTF_8);
      assertThat(svg.toLowerCase(Locale.ROOT))
          .as(link.getIcon())
          .doesNotContain("<script")
          .doesNotContain("foreignobject")
          .doesNotContainPattern("\\son[a-z]+\\s*=")
          .doesNotContain("href=\"http");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"messages.properties", "messages_de.properties", "messages_en.properties"})
  void everyBundleCarriesTheCopy(String bundle) throws IOException {
    Properties messages = new Properties();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
      assertThat(in).as(bundle).isNotNull();
      messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    List<String> required =
        Stream.of(
                Stream.of(
                    "nav.section.resources",
                    "nav.scLinks",
                    "scLinks.title",
                    "scLinks.intro",
                    "scLinks.newTab",
                    "scLinks.disclaimer"),
                Arrays.stream(ScLinkCategory.values())
                    .map(category -> "scLinks.category." + category.getKey()),
                Arrays.stream(ScLink.values())
                    .flatMap(
                        link ->
                            Stream.of(
                                "scLinks.link." + link.getKey() + ".name",
                                "scLinks.link." + link.getKey() + ".description")))
            .flatMap(s -> s)
            .toList();
    for (String key : required) {
      assertThat(messages.getProperty(key)).as(bundle + " " + key).isNotBlank();
    }
  }
}
