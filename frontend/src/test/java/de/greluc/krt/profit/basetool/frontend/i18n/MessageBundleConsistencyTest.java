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

package de.greluc.krt.profit.basetool.frontend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static checks of the frontend message bundles: the locales declare the same keys, the German
 * bundle has no literal umlauts, no key is declared twice, the default bundle declares every key
 * and mirrors the German values, and every static template key is declared.
 *
 * <p>Reads the source files under {@code src/main/resources} directly.
 */
class MessageBundleConsistencyTest {

  /** German locale bundle under the module's main resources. */
  private static final Path DE = Path.of("src/main/resources/messages_de.properties");

  /** English locale bundle under the module's main resources. */
  private static final Path EN = Path.of("src/main/resources/messages_en.properties");

  /** Default (no-locale) fallback bundle under the module's main resources. */
  private static final Path DEFAULT = Path.of("src/main/resources/messages.properties");

  /**
   * The seven German umlaut characters that CLAUDE.md requires to be written as {@code \\uXXXX}
   * escapes inside {@code .properties} files. Declared as Java unicode escapes so this source file
   * itself stays pure ASCII.
   */
  private static final String LITERAL_UMLAUTS = "äöüÄÖÜß";

  /**
   * Asserts the German and English bundles declare an identical key set, listing any key that
   * exists in only one of them so the missing translation is obvious from the failure message.
   *
   * @throws IOException if either bundle cannot be read from disk
   */
  @Test
  void germanAndEnglishBundlesDeclareTheSameKeys() throws IOException {
    Set<String> deKeys = keysOf(DE);
    Set<String> enKeys = keysOf(EN);

    assertThat(difference(deKeys, enKeys)).as("keys present in DE but missing in EN").isEmpty();
    assertThat(difference(enKeys, deKeys)).as("keys present in EN but missing in DE").isEmpty();
  }

  /**
   * Asserts the German bundle contains no literal umlaut characters, enforcing the {@code \\uXXXX}
   * escaping rule from CLAUDE.md. Offending lines are reported with their 1-based line number.
   *
   * @throws IOException if the German bundle cannot be read from disk
   */
  @Test
  void germanBundleEscapesUmlautsAsUnicode() throws IOException {
    List<String> offenders = new ArrayList<>();
    List<String> lines = Files.readAllLines(DE, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      for (int c = 0; c < line.length(); c++) {
        if (LITERAL_UMLAUTS.indexOf(line.charAt(c)) >= 0) {
          offenders.add((i + 1) + ": " + line);
          break;
        }
      }
    }

    assertThat(offenders)
        .as("German umlauts must be written as \\uXXXX escapes in messages_de.properties")
        .isEmpty();
  }

  /**
   * Asserts that no bundle declares a key twice, reading the raw lines because {@link
   * Properties#load(Reader)} silently keeps the last value.
   *
   * @throws IOException if a bundle cannot be read from disk
   */
  @Test
  void noBundleDeclaresDuplicateKeys() throws IOException {
    assertThat(duplicateKeys(DEFAULT))
        .as("duplicate keys in the default bundle (messages.properties)")
        .isEmpty();
    assertThat(duplicateKeys(DE)).as("duplicate keys in messages_de.properties").isEmpty();
    assertThat(duplicateKeys(EN)).as("duplicate keys in messages_en.properties").isEmpty();
  }

  /**
   * Asserts that the no-locale default bundle declares every key present in the German and English
   * bundles, and declares no key absent from both. A key missing from {@code messages.properties}
   * falls back to its raw {@code some.key} for any locale that is neither {@code de} nor {@code
   * en}.
   *
   * @throws IOException if a bundle cannot be read from disk
   */
  @Test
  void defaultBundleDeclaresEveryLocaleKey() throws IOException {
    Set<String> defaultKeys = keysOf(DEFAULT);
    Set<String> deKeys = keysOf(DE);
    Set<String> enKeys = keysOf(EN);

    assertThat(difference(deKeys, defaultKeys))
        .as("keys in DE but missing from the default bundle (messages.properties)")
        .isEmpty();
    assertThat(difference(enKeys, defaultKeys))
        .as("keys in EN but missing from the default bundle (messages.properties)")
        .isEmpty();

    Set<String> localeKeys = new TreeSet<>(deKeys);
    localeKeys.addAll(enKeys);
    assertThat(difference(defaultKeys, localeKeys))
        .as("keys in the default bundle that exist in neither locale bundle")
        .isEmpty();
  }

  /**
   * Asserts that the default bundle resolves every key to the same value as the German bundle.
   *
   * @throws IOException if a bundle cannot be read from disk
   */
  @Test
  void defaultBundleMirrorsGermanValues() throws IOException {
    Properties defaultProps = load(DEFAULT);
    Properties deProps = load(DE);
    List<String> mismatches = new ArrayList<>();
    for (String key : new TreeSet<>(deProps.stringPropertyNames())) {
      String defaultValue = defaultProps.getProperty(key);
      if (defaultValue == null || !defaultValue.equals(deProps.getProperty(key))) {
        mismatches.add(key);
      }
    }
    assertThat(mismatches)
        .as("keys whose default-bundle value differs from the German bundle")
        .isEmpty();
  }

  /** HTML comments, stripped before the key scan so documentation is not read as markup. */
  private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  /**
   * Every static {@code #{...}} key a template uses is declared by the bundles; dynamic keys are
   * skipped.
   *
   * @throws IOException when a template or bundle cannot be read
   */
  @Test
  void everyMessageKeyUsedByATemplateIsDeclared() throws IOException {
    Set<String> declared = keysOf(DEFAULT);
    Pattern key = Pattern.compile("#\\{\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)\\s*\\}");
    List<String> missing = new ArrayList<>();

    try (Stream<Path> templates = Files.walk(Path.of("src/main/resources/templates"))) {
      for (Path template : templates.filter(f -> f.toString().endsWith(".html")).toList()) {
        String body =
            COMMENT.matcher(Files.readString(template, StandardCharsets.UTF_8)).replaceAll("");
        Matcher matcher = key.matcher(body);
        while (matcher.find()) {
          if (!declared.contains(matcher.group(1))) {
            missing.add(matcher.group(1) + "  (" + template.getFileName() + ")");
          }
        }
      }
    }

    assertThat(new TreeSet<>(missing))
        .as(
            "templates asking for a message key no bundle declares — Thymeleaf renders these as"
                + " ??key_locale?? to the user instead of failing")
        .isEmpty();
  }

  /**
   * Returns the declared keys of a bundle, sorted, as parsed by {@link #load(Path)}.
   *
   * @param path the bundle to read
   * @return the bundle's keys in sorted order
   * @throws IOException if the bundle cannot be read
   */
  private static Set<String> keysOf(Path path) throws IOException {
    return new TreeSet<>(load(path).stringPropertyNames());
  }

  /**
   * Loads a bundle via {@link Properties#load(Reader)}, with resolved (unescaped) values.
   *
   * @param path the bundle to read
   * @return the parsed properties
   * @throws IOException if the bundle cannot be read
   */
  private static Properties load(Path path) throws IOException {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  /**
   * Returns the keys of {@code left} that are absent from {@code right}, as a new sorted set.
   *
   * @param left the source key set
   * @param right the key set whose members are excluded
   * @return the elements of {@code left} not contained in {@code right}
   */
  private static Set<String> difference(Set<String> left, Set<String> right) {
    Set<String> result = new TreeSet<>(left);
    result.removeAll(right);
    return result;
  }

  /**
   * Collects keys declared more than once in a bundle by walking its raw lines, honouring line
   * continuations and comment lines.
   *
   * @param path the bundle to scan
   * @return the duplicated keys in first-seen order; empty when every key is unique
   * @throws IOException if the bundle cannot be read
   */
  private static List<String> duplicateKeys(Path path) throws IOException {
    List<String> duplicates = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    boolean inContinuation = false;
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (inContinuation) {
        inContinuation = continuesToNextLine(line);
        continue;
      }
      String stripped = line.stripLeading();
      if (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == '!') {
        inContinuation = false;
        continue;
      }
      String key = parseKey(stripped);
      if (!key.isEmpty() && !seen.add(key)) {
        duplicates.add(key);
      }
      inContinuation = continuesToNextLine(line);
    }
    return duplicates;
  }

  /**
   * Reports whether a physical line continues onto the next via a trailing run of an odd number of
   * backslashes, matching {@code java.util.Properties} line-continuation semantics.
   *
   * @param line the physical line to inspect
   * @return {@code true} if the following physical line continues this logical line
   */
  private static boolean continuesToNextLine(String line) {
    int backslashes = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      backslashes++;
    }
    return (backslashes & 1) == 1;
  }

  /**
   * Extracts the key of a property declaration: everything before the first unescaped {@code =},
   * {@code :} or whitespace.
   *
   * @param stripped the property line without leading whitespace
   * @return the declared key; empty when the line carries none
   */
  private static String parseKey(String stripped) {
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < stripped.length(); i++) {
      char c = stripped.charAt(i);
      if (c == '\\') {
        if (i + 1 < stripped.length()) {
          key.append(c).append(stripped.charAt(++i));
        }
        continue;
      }
      if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') {
        break;
      }
      key.append(c);
    }
    return key.toString();
  }
}
