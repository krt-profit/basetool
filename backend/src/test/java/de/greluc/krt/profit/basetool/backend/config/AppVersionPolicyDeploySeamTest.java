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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the deploy seam of the release-bound Android floor (REQ-API-020): the floor is a literal
 * committed in {@code application.yml}, the host can reach it only through the {@code _OVERRIDE}
 * variables, and no compose file, environment template or profile passes the retired variable
 * names, so a value left in a host {@code .env} cannot pin the floor.
 */
class AppVersionPolicyDeploySeamTest {

  /** The prefix of the bound policy. */
  private static final String PREFIX = "app.android.version-policy.";

  /** A retired variable name used bare, not as the stem of its {@code _OVERRIDE} successor. */
  private static final Pattern RETIRED_NAME =
      Pattern.compile(
          "\\bAPP_ANDROID_(MINIMUM_VERSION_CODE|LATEST_VERSION_CODE|RELEASES_URL)\\b(?!_)");

  /** The override lines the backend environment must carry, each empty by default. */
  private static final List<String> OVERRIDE_LINES =
      List.of(
          "APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE=${APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE:-}",
          "APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE=${APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE:-}",
          "APP_ANDROID_RELEASES_URL_OVERRIDE=${APP_ANDROID_RELEASES_URL_OVERRIDE:-}");

  @Test
  @DisplayName("the release floor is a committed literal, not a host placeholder")
  void theReleaseFloorIsACommittedLiteral() throws IOException {
    assertThat(declared("application.yml", PREFIX + "release.minimum-version-code"))
        .as("the floor rides the release only if it is a literal in the jar")
        .isInstanceOf(Integer.class);
    assertThat(declared("application.yml", PREFIX + "release.latest-version-code"))
        .isInstanceOf(Integer.class);
    assertThat(String.valueOf(declared("application.yml", PREFIX + "release.releases-url")))
        .startsWith("https://")
        .doesNotContain("${");
  }

  @Test
  @DisplayName("the host reaches the policy only through the _OVERRIDE variables, empty by default")
  void theHostReachesThePolicyOnlyThroughTheOverride() throws IOException {
    assertThat(declared("application.yml", PREFIX + "emergency-override.minimum-version-code"))
        .isEqualTo("${APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE:}");
    assertThat(declared("application.yml", PREFIX + "emergency-override.latest-version-code"))
        .isEqualTo("${APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE:}");
    assertThat(declared("application.yml", PREFIX + "emergency-override.releases-url"))
        .isEqualTo("${APP_ANDROID_RELEASES_URL_OVERRIDE:}");
  }

  /**
   * A profile file must not redeclare the policy, or the committed default would differ per
   * profile.
   *
   * @param yaml the profile file
   * @throws IOException if it cannot be read
   */
  @ParameterizedTest
  @ValueSource(strings = {"application-dev.yml", "application-prod.yml", "application-test.yml"})
  @DisplayName("no profile redeclares the policy")
  void noProfileRedeclaresThePolicy(String yaml) throws IOException {
    List<String> keys = new ArrayList<>();
    for (PropertySource<?> source :
        new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml))) {
      if (source instanceof EnumerablePropertySource<?> enumerable) {
        for (String name : enumerable.getPropertyNames()) {
          if (name.startsWith("app.android.")) {
            keys.add(name);
          }
        }
      }
    }
    assertThat(keys).as("%s declares the Android policy", yaml).isEmpty();
  }

  @Test
  @DisplayName("the backend's Quadlet environment passes exactly the override variables")
  void theQuadletEnvironmentPassesTheOverride() throws IOException {
    List<String> lines =
        Files.readAllLines(
            repositoryRoot().resolve("quadlet/env.d/backend.env.tmpl"), StandardCharsets.UTF_8);
    assertThat(lines).containsAll(OVERRIDE_LINES);
  }

  @Test
  @DisplayName("no deploy file, profile or template passes a retired variable name")
  void noDeployFilePassesARetiredName() throws IOException {
    List<Path> files = deployFiles();
    assertThat(files)
        .as("the sweep must find the compose files, env.d templates and backend profiles")
        .hasSizeGreaterThanOrEqualTo(30);

    List<String> hits = new ArrayList<>();
    for (Path file : files) {
      hits.addAll(retiredNamesIn(file.toString(), Files.readString(file, StandardCharsets.UTF_8)));
    }
    assertThat(hits)
        .as(
            "a retired name would carry a stale host value into the container; use the"
                + " _OVERRIDE variable")
        .isEmpty();
  }

  @Test
  @DisplayName("control: the retired-name scan finds a planted stale line")
  void theScanFindsAPlantedStaleLine() {
    String planted =
        "APP_ANDROID_MINIMUM_VERSION_CODE=${APP_ANDROID_MINIMUM_VERSION_CODE:-0}\n"
            + "APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE=${APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE:-}\n"
            + "    APP_ANDROID_RELEASES_URL: ${APP_ANDROID_RELEASES_URL:-https://x}\n";
    assertThat(retiredNamesIn("planted", planted))
        .containsExactly(
            "planted:1 APP_ANDROID_MINIMUM_VERSION_CODE",
            "planted:1 APP_ANDROID_MINIMUM_VERSION_CODE",
            "planted:3 APP_ANDROID_RELEASES_URL",
            "planted:3 APP_ANDROID_RELEASES_URL");
  }

  /**
   * Finds every bare retired variable name in a text.
   *
   * @param name the label of the text, used in each hit
   * @param text the text to scan
   * @return one {@code label:line NAME} entry per occurrence
   */
  static List<String> retiredNamesIn(String name, String text) {
    List<String> hits = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher matcher = RETIRED_NAME.matcher(lines[i]);
      while (matcher.find()) {
        hits.add(name + ":" + (i + 1) + " " + matcher.group());
      }
    }
    return hits;
  }

  /**
   * Lists the files that decide what reaches the backend's environment: every compose file, every
   * env.d template and every backend profile.
   *
   * @return the files, sorted
   * @throws IOException if a directory cannot be listed
   */
  private static List<Path> deployFiles() throws IOException {
    Path root = repositoryRoot();
    List<Path> files = new ArrayList<>();
    try (Stream<Path> compose = Files.list(root)) {
      compose
          .filter(p -> p.getFileName().toString().matches("docker-compose.*\\.ya?ml"))
          .forEach(files::add);
    }
    try (Stream<Path> templates = Files.list(root.resolve("quadlet/env.d"))) {
      templates.filter(p -> p.getFileName().toString().endsWith(".env.tmpl")).forEach(files::add);
    }
    try (Stream<Path> profiles = Files.list(root.resolve("backend/src/main/resources"))) {
      profiles
          .filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml"))
          .forEach(files::add);
    }
    files.add(root.resolve("backend/src/test/resources/application-test.yml"));
    files.add(root.resolve(".env.example"));
    files.sort(null);
    return files;
  }

  /**
   * Reads one raw declared property from a classpath YAML file without resolving placeholders.
   *
   * @param yaml the classpath name of the file
   * @param key the fully-qualified key
   * @return the declared value, or {@code null}
   * @throws IOException if the file cannot be read
   */
  private static Object declared(String yaml, String key) throws IOException {
    return new YamlPropertySourceLoader()
        .load(yaml, new ClassPathResource(yaml)).stream()
            .map(source -> source.getProperty(key))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
  }

  /**
   * Walks up from the working directory to the repository root, marked by {@code
   * settings.gradle.kts}.
   *
   * @return the repository root
   */
  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("settings.gradle.kts"))) {
        return p;
      }
    }
    throw new IllegalStateException(
        "repository root not found above " + Paths.get("").toAbsolutePath());
  }
}
