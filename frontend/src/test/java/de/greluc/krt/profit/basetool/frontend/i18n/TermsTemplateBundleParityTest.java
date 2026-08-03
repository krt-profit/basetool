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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the Terms of Use page against its message bundle. The terms are a contract text: a clause
 * that exists only as a {@code terms.*} key is never shown to any user and therefore never becomes
 * part of the agreement, while a {@code #{terms.*}} reference with no key renders the raw key as
 * the clause body. Neither failure mode is visible in a controller or render test — {@link
 * de.greluc.krt.profit.basetool.frontend.controller.TermsControllerTest} asserts only that the view
 * resolves — so both are pinned here. This caught nothing at the time it was written (the sets were
 * already equal); it exists because section 4 was renumbered when the approved-client-software
 * obligation was inserted as bullet 5, and a renumbering is exactly where a bullet silently goes
 * missing.
 *
 * <p>Reads the committed sources under {@code src/main/resources} directly rather than the
 * classpath copy, matching {@link MessageBundleConsistencyTest}; the Gradle {@code Test} task runs
 * with the module directory as its working directory.
 */
class TermsTemplateBundleParityTest {

  /** The Terms of Use Thymeleaf template under the module's main resources. */
  private static final Path TEMPLATE = Path.of("src/main/resources/templates/terms.html");

  /** German locale bundle, the authoritative source of the terms wording. */
  private static final Path BUNDLE = Path.of("src/main/resources/messages_de.properties");

  /** Matches a Thymeleaf message expression referencing a {@code terms.*} key. */
  private static final Pattern TERMS_EXPRESSION = Pattern.compile("#\\{(terms\\.[A-Za-z0-9_]+)}");

  /** Key prefix that scopes both sides of the comparison to the Terms of Use text. */
  private static final String TERMS_PREFIX = "terms.";

  /**
   * Asserts that the set of {@code terms.*} keys referenced by {@code terms.html} is exactly the
   * set declared in the German bundle, reporting each side of the difference separately so the
   * failure names the orphaned clause rather than just a count mismatch.
   *
   * @throws IOException if the template or the German bundle cannot be read from disk
   */
  @Test
  void everyTermsClauseIsBothDeclaredAndRendered() throws IOException {
    Set<String> referenced = referencedKeys();
    Set<String> declared = declaredKeys();

    assertThat(difference(declared, referenced))
        .as("terms.* keys declared in the bundle but never rendered by terms.html")
        .isEmpty();
    assertThat(difference(referenced, declared))
        .as("terms.* keys rendered by terms.html but not declared in the bundle")
        .isEmpty();
  }

  /**
   * Collects every {@code terms.*} key that {@code terms.html} resolves through a {@code #{...}}
   * message expression, including the ones inside {@code th:text} on list items.
   *
   * @return the referenced keys, sorted for a deterministic failure message
   * @throws IOException if the template cannot be read from disk
   */
  private static Set<String> referencedKeys() throws IOException {
    String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    Set<String> keys = new TreeSet<>();
    Matcher matcher = TERMS_EXPRESSION.matcher(template);
    while (matcher.find()) {
      keys.add(matcher.group(1));
    }
    return keys;
  }

  /**
   * Collects every {@code terms.*} key declared in the German bundle, ignoring comment and blank
   * lines and taking the key as everything before the first {@code =}.
   *
   * @return the declared keys, sorted for a deterministic failure message
   * @throws IOException if the German bundle cannot be read from disk
   */
  private static Set<String> declaredKeys() throws IOException {
    List<String> lines = Files.readAllLines(BUNDLE, StandardCharsets.UTF_8);
    Set<String> keys = new TreeSet<>();
    for (String line : lines) {
      if (line.startsWith(TERMS_PREFIX) && line.indexOf('=') > 0) {
        keys.add(line.substring(0, line.indexOf('=')).trim());
      }
    }
    return keys;
  }

  /**
   * Returns the keys present in {@code left} but absent from {@code right}, sorted so the assertion
   * message lists them in a stable order.
   *
   * @param left the set whose surplus entries are wanted
   * @param right the set to subtract
   * @return the sorted set difference {@code left \ right}
   */
  private static Set<String> difference(Set<String> left, Set<String> right) {
    Set<String> result = new TreeSet<>(left);
    result.removeAll(right);
    return result;
  }
}
