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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The build gate that keeps the backend's live-sync registry a subset of the frontend's (ADR-0143).
 *
 * <p>The two modules cannot share code — there is no dependency between them, by design — but they
 * do share a Redis channel and a payload. A prefix or a section key that exists on one side and not
 * the other produces the worst failure shape this bridge has: nothing throws, nothing is logged, a
 * screen simply stops updating. That is invisible in production and invisible in every test that
 * exercises one module alone, which is why it is caught here, by reading the other module's source.
 *
 * <p>Parsing a sibling's source as a gate is the established move in this repo — the frontend's own
 * {@code LiveSyncSectionMapParityTest} derives the client seam maps from the shipped JavaScript for
 * exactly this reason. The parse is deliberately narrow: it reads the first three constructor
 * arguments of each enum constant and nothing else, so a change to any other argument leaves it
 * alone.
 */
class LiveSyncTopicRegistryParityTest {

  /** Path of the frontend registry, relative to the repository root. */
  private static final String FRONTEND_REGISTRY =
      "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/websocket/LiveSyncTopicClass.java";

  /** An enum constant's name and the start of its argument list. */
  private static final Pattern CONSTANT = Pattern.compile("(?m)^ {2}([A-Z][A-Z0-9_]*)\\s*\\(");

  /** The first string literal in an argument list — the wire prefix. */
  private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");

  /** The first boolean in an argument list — whether the class is per-resource. */
  private static final Pattern FIRST_BOOLEAN = Pattern.compile("\\b(true|false)\\b");

  /** The section whitelist. Its literals never contain a parenthesis, so this stays non-greedy. */
  private static final Pattern SECTIONS = Pattern.compile("Set\\.of\\(([^)]*)\\)");

  private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

  @Test
  @DisplayName("every backend topic class exists in the frontend with the same arity")
  void everyBackendClassExistsInTheFrontend() throws IOException {
    Map<String, FrontendClass> frontend = parseFrontendRegistry();

    for (LiveSyncTopicClass backendClass : LiveSyncTopicClass.values()) {
      String key = key(backendClass.prefix(), backendClass.perResource());
      assertThat(frontend)
          .as(
              "backend class %s (prefix '%s', per-resource %s) must exist in the frontend registry"
                  + " — the two publish onto one Redis channel, so a room only this side knows is a"
                  + " room whose frames nobody sends",
              backendClass, backendClass.prefix(), backendClass.perResource())
          .containsKey(key);
    }
  }

  @Test
  @DisplayName("every backend section is a section the frontend also names")
  void everyBackendSectionExistsInTheFrontend() throws IOException {
    Map<String, FrontendClass> frontend = parseFrontendRegistry();

    for (LiveSyncTopicClass backendClass : LiveSyncTopicClass.values()) {
      FrontendClass peer = frontend.get(key(backendClass.prefix(), backendClass.perResource()));
      if (peer == null) {
        // Reported by the sibling test; not worth failing twice for one cause.
        continue;
      }
      assertThat(peer.sections())
          .as(
              "sections of backend class %s must all exist in the frontend's %s — a key only this"
                  + " side knows is clipped away on arrival there, so the web never refreshes that"
                  + " region after an app write",
              backendClass, peer.name())
          .containsAll(backendClass.allowedSections());
    }
  }

  @Test
  @DisplayName("the frontend's staff-only rooms stay out of the backend registry")
  void staffOnlyRoomsAreNotBridged() throws IOException {
    Map<String, FrontendClass> frontend = parseFrontendRegistry();
    // Guards the omission rather than the presence: the admin area is web-only permanently (app
    // plan Q7), so these must never drift in by someone "completing" the registry.
    assertThat(frontend).containsKey(key("bank", false));

    Set<String> backendKeys = new LinkedHashSet<>();
    for (LiveSyncTopicClass backendClass : LiveSyncTopicClass.values()) {
      backendKeys.add(key(backendClass.prefix(), backendClass.perResource()));
    }
    assertThat(backendKeys)
        .as("the bank staff room, the members room and the org-structure room are web-only")
        .doesNotContain(key("bank", false), key("members", false), key("org-structure", false));
  }

  /**
   * Reads and parses the frontend registry.
   *
   * @return each frontend class by prefix and arity
   * @throws IOException if the source cannot be read
   */
  private static Map<String, FrontendClass> parseFrontendRegistry() throws IOException {
    Path source = repositoryRoot().resolve(FRONTEND_REGISTRY);
    if (!Files.isRegularFile(source)) {
      return fail(
          "Frontend live-sync registry not found at %s. It is the other half of this bridge; if it"
              + " moved, move this gate with it rather than deleting it.",
          source);
    }
    String text = stripComments(Files.readString(source, StandardCharsets.UTF_8));
    String constants = text.substring(text.indexOf('{') + 1);

    Map<String, FrontendClass> parsed = new LinkedHashMap<>();
    Matcher constant = CONSTANT.matcher(constants);
    while (constant.find()) {
      String name = constant.group(1);
      String args = argumentList(constants, constant.end() - 1);
      if (args == null) {
        continue;
      }
      Matcher prefix = FIRST_STRING.matcher(args);
      Matcher perResource = FIRST_BOOLEAN.matcher(args);
      Matcher sections = SECTIONS.matcher(args);
      if (!prefix.find() || !perResource.find() || !sections.find()) {
        continue;
      }
      parsed.put(
          key(prefix.group(1), Boolean.parseBoolean(perResource.group(1))),
          new FrontendClass(name, literals(sections.group(1))));
    }
    assertThat(parsed)
        .as("the frontend registry parsed to nothing — the parse, not the registry, is broken")
        .isNotEmpty();
    return parsed;
  }

  /**
   * Extracts the balanced argument list starting at an opening parenthesis.
   *
   * @param text the enum body
   * @param open index of the {@code (}
   * @return the text between the parentheses, or {@code null} if they never balance
   */
  private static String argumentList(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return text.substring(open + 1, i);
        }
      }
    }
    return null;
  }

  /**
   * Pulls the string literals out of a {@code Set.of(...)} argument.
   *
   * @param raw the text between the parentheses
   * @return the literal values
   */
  private static Set<String> literals(String raw) {
    Set<String> values = new LinkedHashSet<>();
    Matcher literal = STRING_LITERAL.matcher(raw);
    while (literal.find()) {
      values.add(literal.group(1));
    }
    return values;
  }

  /**
   * Removes block and line comments so a prefix or a section mentioned in prose cannot be parsed as
   * a declaration.
   *
   * @param source the Java source
   * @return the source with comments blanked
   */
  private static String stripComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
  }

  /**
   * Walks up from the working directory to the repository root.
   *
   * <p>Gradle runs a module's tests with the module directory as the working directory, but that is
   * a default rather than a guarantee, so the root is found by looking for it instead of assuming
   * one level up.
   *
   * @return the directory holding both modules
   */
  private static Path repositoryRoot() {
    Path candidate = Paths.get("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("frontend/src/main/java"))
          && Files.isDirectory(candidate.resolve("backend/src/main/java"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    return fail("Could not locate the repository root from %s", Paths.get("").toAbsolutePath());
  }

  /**
   * Builds the lookup key, which is prefix plus arity because prefix alone collides.
   *
   * @param prefix the wire prefix
   * @param perResource whether the class names a resource
   * @return the key
   */
  private static String key(String prefix, boolean perResource) {
    return prefix + (perResource ? ":{id}" : "");
  }

  /**
   * One parsed frontend enum constant.
   *
   * @param name the constant's name, used only in failure messages
   * @param sections its section whitelist
   */
  private record FrontendClass(String name, Set<String> sections) {}
}
