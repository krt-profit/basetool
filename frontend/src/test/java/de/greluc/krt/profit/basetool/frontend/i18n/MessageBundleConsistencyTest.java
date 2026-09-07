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
 * Static lint of the frontend message bundles. A user-visible string added to one locale but not
 * the other otherwise surfaces only as a raw {@code operation.some.key} at render time; a literal
 * umlaut in the German bundle breaks the project's {@code \\uXXXX} encoding rule; a key declared
 * twice silently shadows its earlier value (how the 2026-05-11 backfill duplicates went unnoticed);
 * a key missing from the no-locale default bundle falls back to its raw key for any locale that is
 * neither {@code de} nor {@code en}; and a default value that drifts from its German counterpart
 * renders stale text for such a locale. All of these are pinned here so they fail the build instead
 * of production. Reads the source files under {@code src/main/resources} directly (the Gradle
 * {@code Test} task runs with the module directory as its working directory) so the assertions see
 * the exact committed bytes, not the processed classpath copy.
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
   * Asserts that no bundle declares the same key twice. {@link Properties#load(Reader)} silently
   * keeps the last value of a repeated key, which is how the 2026-05-11 backfill left dozens of
   * duplicate declarations per bundle undetected; this reads the raw lines so a re-introduced
   * duplicate fails the build.
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
   * Asserts that the no-locale default bundle resolves every key to the same value as the German
   * bundle. The default bundle is the German fallback, so it must mirror {@code messages_de}; a
   * drift means a locale that is neither {@code de} nor {@code en} renders stale or wrong-language
   * text.
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
   * Every {@code #{...}} a template asks for is declared by the bundles.
   *
   * <p>The cases above compare the bundles only with each other, so all of them are satisfied by
   * deleting a key from all three at once — which is exactly how {@code orders.create.link} went.
   * Its anonymous use went with the public order form (ADR-0149) and the key with it; the
   * authenticated one in the sidebar survived, and every signed-in member for whom neither {@code
   * canViewJobOrders} nor {@code canViewOwnJobOrders} holds (the non-profit-unit case {@code
   * CapabilityFlagsAdvice} preserves deliberately) then read {@code ??orders.create.link_de??} as a
   * nav label on every page of the tool. Nothing failed: Thymeleaf renders the marker and carries
   * on.
   *
   * <p>Only static keys are checked. A {@code #{'prefix.' + ${x}}} cannot be resolved without
   * running the page, so the scan skips anything that is not a bare dotted literal rather than
   * guessing at it.
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
        // Comments are stripped first: fragments/components.html documents its own call shape in
        // an HTML comment, keys and all, and a key nobody renders is not a key anybody misses.
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
   * Returns the declared property keys of a bundle in stable sorted order, parsed via {@link
   * #load(Path)} (which handles comments, {@code =}/{@code :}/space separators, line continuations
   * and escapes correctly).
   *
   * @param path the bundle to read
   * @return the bundle's keys in stable sorted order
   * @throws IOException if the bundle cannot be read
   */
  private static Set<String> keysOf(Path path) throws IOException {
    return new TreeSet<>(load(path).stringPropertyNames());
  }

  /**
   * Loads a bundle via {@link Properties#load(Reader)}, exposing the resolved (unescaped) values so
   * callers can compare them across bundles.
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
   * Computes {@code left \\ right} (the keys in {@code left} absent from {@code right}) as a new
   * sorted set, leaving the inputs untouched.
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
   * Collects keys declared more than once in a bundle. Unlike {@link Properties#load(Reader)} --
   * which silently keeps the last value of a repeated key and so hides duplicates -- this walks the
   * raw physical lines (honouring line continuations and {@code #}/{@code !} comment lines) and
   * records every key whose declaration is seen a second time.
   *
   * @param path the bundle to scan
   * @return the duplicated keys in first-seen order (empty when every key is unique)
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
   * Extracts the key of a property declaration: everything up to the first unescaped {@code =},
   * {@code :} or whitespace separator, matching {@code java.util.Properties} key parsing for the
   * flat single-line declarations these bundles use.
   *
   * @param stripped the property line with leading whitespace already removed
   * @return the declared key (empty when the line carries no key)
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
