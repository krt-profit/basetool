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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests that every literal i18n key a browser script reads through {@code window.krtI18nText} or a
 * {@code krtFetch.sectionWrite} config is provided by some template, as a {@code data-*} attribute
 * or a bootstrap object property.
 *
 * <p>Keys built at run time are not covered.
 */
class I18nDictionaryCoverageTest {

  private static final Path JS = Path.of("src/main/resources/static/js");
  private static final Path TEMPLATES = Path.of("src/main/resources/templates");

  /** A literal key as the last argument of {@code krtI18nText(…)}. */
  private static final Pattern CALL_KEY =
      Pattern.compile(
          "krtI18nText\\((?:[^;]*?),\\s*'([A-Za-z_][\\w.\\-\\[\\]]*)'\\s*,?\\s*\\)",
          Pattern.DOTALL);

  /** A {@code sectionWrite} config's dictionary name. */
  private static final Pattern DICT_NAME = Pattern.compile("dictName:\\s*'(\\w+)'");

  /** A {@code sectionWrite} config's {@code …Key: 'message.key'} entry. */
  private static final Pattern CONFIG_KEY = Pattern.compile("\\w+Key:\\s*'([\\w.]+)'");

  /**
   * Collects every checked key with the script it came from.
   *
   * @return key to the first script that reads it
   * @throws IOException if a script cannot be read
   */
  private static Map<String, String> readKeys() throws IOException {
    Map<String, String> keys = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(JS)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
        String js = Files.readString(file, StandardCharsets.UTF_8);
        Matcher call = CALL_KEY.matcher(js);
        while (call.find()) {
          keys.putIfAbsent(call.group(1), file.getFileName().toString());
        }
        Matcher dictName = DICT_NAME.matcher(js);
        while (dictName.find()) {
          int end = js.indexOf("sections:", dictName.end());
          Matcher configKey =
              CONFIG_KEY.matcher(js.substring(dictName.end(), end < 0 ? js.length() : end));
          while (configKey.find()) {
            keys.putIfAbsent(
                dictName.group(1) + "[" + configKey.group(1) + "]", file.getFileName().toString());
          }
        }
      }
    }
    return keys;
  }

  /**
   * Reads every template.
   *
   * @return template path to its text
   * @throws IOException if a template cannot be read
   */
  private static Map<Path, String> readTemplates() throws IOException {
    Map<Path, String> templates = new LinkedHashMap<>();
    try (Stream<Path> files = Files.walk(TEMPLATES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".html")).sorted().toList()) {
        templates.put(file, Files.readString(file, StandardCharsets.UTF_8));
      }
    }
    return templates;
  }

  /**
   * Returns why a key has no source, or {@code null} when every page provides it.
   *
   * @param key the key a script reads
   * @param templates every template's text
   * @return the problem, or {@code null}
   */
  private static String problem(String key, Map<Path, String> templates) {
    if (key.startsWith("data-")) {
      Pattern attribute = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=");
      return templates.values().stream().anyMatch(t -> attribute.matcher(t).find())
          ? null
          : "no template emits " + key;
    }
    if (key.indexOf('.') < 0 && key.indexOf('[') < 0) {
      Pattern global = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=[^=]");
      return templates.values().stream().anyMatch(t -> global.matcher(t).find())
          ? null
          : "no template assigns " + key;
    }
    String name;
    String property;
    int bracket = key.indexOf('[');
    if (bracket > 0) {
      name = key.substring(0, bracket);
      property = key.substring(bracket + 1, key.length() - 1);
    } else {
      name = key.substring(0, key.indexOf('.'));
      property = key.substring(key.lastIndexOf('.') + 1);
    }
    Pattern declaration = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\\{");
    Pattern merge = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*Object\\.assign\\(");
    Pattern entry =
        Pattern.compile(
            "(?:['\"]"
                + Pattern.quote(property)
                + "['\"]|\\b"
                + Pattern.quote(property)
                + ")\\s*:");
    List<String> missing = new ArrayList<>();
    int declaring = 0;
    boolean mergedFound = false;
    boolean merged = false;
    for (Map.Entry<Path, String> template : templates.entrySet()) {
      String text = template.getValue();
      if (merge.matcher(text).find()) {
        merged = true;
        mergedFound |= entry.matcher(text).find();
      } else if (declaration.matcher(text).find()) {
        declaring++;
        if (!entry.matcher(text).find()) {
          missing.add(TEMPLATES.relativize(template.getKey()).toString());
        }
      }
    }
    if (declaring == 0 && !merged) {
      return "no template declares " + name;
    }
    if (merged && declaring == 0 && !mergedFound) {
      return "no contributor to " + name + " provides " + property;
    }
    return missing.isEmpty() ? null : name + " lacks " + property + " in " + missing;
  }

  /**
   * Every checked key has a source on every page that declares its dictionary.
   *
   * @throws IOException if a script or template cannot be read
   */
  @Test
  void everyLocalizedStringAScriptReadsIsProvidedByItsPages() throws IOException {
    Map<String, String> keys = readKeys();
    Map<Path, String> templates = readTemplates();

    TreeSet<String> problems = new TreeSet<>();
    keys.forEach(
        (key, script) -> {
          String problem = problem(key, templates);
          if (problem != null) {
            problems.add(script + ": " + key + " -> " + problem);
          }
        });

    assertThat(keys).as("the scan must find the migrated call sites").hasSizeGreaterThan(100);
    assertThat(problems).as("localized strings no page provides").isEmpty();
  }

  /**
   * No script falls back to a hardcoded user-visible literal after {@code ||} any more.
   *
   * @throws IOException if a script cannot be read
   */
  @Test
  void noScriptFallsBackToALiteralDefault() throws IOException {
    Pattern fallback =
        Pattern.compile(
            "\\|\\|\\s*'([^'\\n]*[A-ZÄÖÜ ][^'\\n]*)'|\\|\\|\\s*'([A-Za-zäöüß][^'\\n]*)'");
    TreeSet<String> hits = new TreeSet<>();
    try (Stream<Path> files = Files.list(JS)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
        String js =
            Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
        Matcher m = fallback.matcher(js);
        while (m.find()) {
          String literal = m.group(1) != null ? m.group(1) : m.group(2);
          if (literal.matches("[a-z][\\w\\-]*")
              || literal.matches("[A-Z][A-Z_]*")
              || literal.matches("accountId|application/json")
              || literal.matches("var\\(--[\\w-]+\\)")
              || literal.matches("[\\w\\-]+\\.pdf")) {
            continue;
          }
          hits.add(file.getFileName() + ": '" + literal + "'");
        }
      }
    }
    assertThat(hits).as("literal UI-text fallbacks; use window.krtI18nText").isEmpty();
  }
}
