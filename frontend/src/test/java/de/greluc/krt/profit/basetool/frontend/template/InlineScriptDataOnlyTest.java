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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins that every inline template script is a server-value bootstrap and nothing else (ADR-0069,
 * REQ-FE-018): page logic lives in a linted, type-checked file under {@code static/js}, and an
 * inline block only hands Thymeleaf values to it as {@code th:inline="javascript"} declarations of
 * literals.
 *
 * <p>A block that genuinely has to stay code is listed in {@link #EXCEPTIONS} with the reason.
 */
class InlineScriptDataOnlyTest {

  /**
   * The reviewed inline blocks that run code, keyed by template and a snippet of the block, each
   * with the reason it cannot live in a file.
   */
  static final Map<String, String> EXCEPTIONS =
      Map.of(
          "fragments/head.html|window.krtEvents = window.krtEvents ||",
          "The krtEvents stub reports, five seconds after load, that event-delegation.js never"
              + " replaced it; it detects a failed deferred script and so must not be one.");

  /** At least this many inline blocks are checked, so a broken scan cannot pass empty. */
  private static final int MIN_INLINE_SCRIPTS = 55;

  /** At least this many templates are scanned. */
  private static final int MIN_TEMPLATES = 100;

  private static final Pattern SCRIPT =
      Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

  private static final Pattern TYPE = Pattern.compile("\\btype\\s*=\\s*\"([^\"]*)\"");

  private static final Set<String> DECLARATIONS = Set.of("const", "let", "var");

  private static final Set<String> KEYWORD_VALUES = Set.of("true", "false", "null");

  @Test
  void everyInlineScriptIsADataBootstrapOrAReviewedException()
      throws IOException, URISyntaxException {
    Scan scan = scan(templatesRoot());
    assertThat(scan.templates()).as("templates scanned").isGreaterThanOrEqualTo(MIN_TEMPLATES);
    assertThat(scan.inlineScripts())
        .as("inline scripts checked")
        .isGreaterThanOrEqualTo(MIN_INLINE_SCRIPTS);
    assertThat(scan.offenders())
        .as(
            "an inline script only declares server values as literals in a th:inline=\"javascript\""
                + " block; move its logic into a static/js module (ADR-0069) or list it in"
                + " EXCEPTIONS with the reason")
        .isEmpty();
    assertThat(scan.unusedExceptions())
        .as("every exception still matches an inline block")
        .isEmpty();
  }

  @Test
  void aPlantedTemplateWithLogicFails() throws IOException, URISyntaxException {
    Path fixtures =
        Paths.get(
                InlineScriptDataOnlyTest.class
                    .getResource("/fixtures/inline-script/planted-logic.html")
                    .toURI())
            .getParent();
    Scan scan = scan(fixtures);
    assertThat(scan.inlineScripts()).isEqualTo(3);
    assertThat(scan.offenders())
        .hasSize(2)
        .anyMatch(o -> o.contains("without th:inline"))
        .anyMatch(o -> o.contains("a statement that is not a declaration"));
  }

  @Test
  void theParserAcceptsTheBootstrapShapes() {
    checkData(
        """
        const A = /*[[#{a}]]*/ 'a';
        var B = { x: /*[[#{x}]]*/ 'x', 'k.y': -1, 2: [true, null, "s"], nested: { z: 1.5 } };
        let materialIndex = /*[[${n}]]*/ 1;
        window.C =
            /*[[${c}]]*/ null;
        window.D = [
            /*[[#{d}]]*/ 'd',
        ];
        const E = /*[[${map}]]*/ {}
        """);
  }

  @Test
  void theParserRejectsCode() {
    for (String code :
        List.of(
            "function bind() {}",
            "document.addEventListener('DOMContentLoaded', bind);",
            "const A = window.B;",
            "window.A = Object.assign({}, { a: 1 });",
            "const A = B ? 1 : 2;",
            "const A = 'a' + 'b';",
            "const A = () => 1;",
            "window.A.b = 1;",
            "const A = { a: 1 }; bind();",
            "const A = 1, B = 2;",
            "const A = `x`;",
            "var MSG = '[[#{notification.success.save}]]';",
            "const A = [[${x}]];")) {
      assertThatThrownBy(() -> checkData(code))
          .as(code)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /**
   * The result of scanning one template tree.
   *
   * @param templates the number of templates read
   * @param inlineScripts the number of executable inline script blocks found
   * @param offenders one line per block that is neither data nor a listed exception
   * @param unusedExceptions the exception keys no block matched
   */
  record Scan(
      int templates, int inlineScripts, List<String> offenders, List<String> unusedExceptions) {}

  /**
   * Checks every inline script under {@code root}.
   *
   * @param root the template tree
   * @return the counts and findings
   * @throws IOException when a template cannot be read
   */
  static Scan scan(Path root) throws IOException {
    List<String> offenders = new ArrayList<>();
    List<String> unused = new ArrayList<>(EXCEPTIONS.keySet());
    int templates = 0;
    int inline = 0;
    try (Stream<Path> tree = Files.walk(root)) {
      for (Path file : tree.filter(p -> p.toString().endsWith(".html")).sorted().toList()) {
        templates++;
        String name = root.relativize(file).toString().replace('\\', '/');
        String html =
            Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        Matcher script = SCRIPT.matcher(html);
        while (script.find()) {
          String attrs = script.group(1);
          if (attrs.contains("src=") || !executable(attrs)) {
            continue;
          }
          inline++;
          String body = script.group(2);
          String exception = exceptionFor(name, body);
          if (exception != null) {
            unused.remove(exception);
            continue;
          }
          String where = name + " line " + (html.substring(0, script.start()).lines().count() + 1);
          if (!attrs.contains("th:inline=\"javascript\"")) {
            offenders.add(where + ": an inline script without th:inline=\"javascript\"");
            continue;
          }
          try {
            checkData(body);
          } catch (IllegalArgumentException notData) {
            offenders.add(where + ": " + notData.getMessage());
          }
        }
      }
    }
    return new Scan(templates, inline, offenders, unused);
  }

  /**
   * Whether a script element is executed by the browser, which a data type such as {@code
   * application/json} is not.
   */
  private static boolean executable(String attrs) {
    Matcher type = TYPE.matcher(attrs);
    if (!type.find()) {
      return true;
    }
    String value = type.group(1).trim().toLowerCase(java.util.Locale.ROOT);
    return value.isEmpty() || value.equals("module") || value.endsWith("javascript");
  }

  /** Returns the exception key that covers this block, or null. */
  private static @Nullable String exceptionFor(String template, String body) {
    for (String key : EXCEPTIONS.keySet()) {
      int bar = key.indexOf('|');
      if (key.substring(0, bar).equals(template) && body.contains(key.substring(bar + 1))) {
        return key;
      }
    }
    return null;
  }

  /**
   * Accepts a script made only of {@code const|let|var NAME = literal} and {@code window.NAME =
   * literal} statements, where a literal is a string, number, {@code true}, {@code false}, {@code
   * null}, or an array or object of literals. Thymeleaf's natural-template value comments are
   * dropped first; any other comment or inline expression is rejected.
   *
   * @param code the script body
   * @throws IllegalArgumentException naming the first token that is not data
   */
  static void checkData(@NotNull String code) {
    new DataParser(tokenize(code)).program();
  }

  /**
   * Splits the script into tokens: identifiers, numbers, quoted strings and one-character
   * punctuators, with comments removed.
   */
  private static List<String> tokenize(String code) {
    List<String> tokens = new ArrayList<>();
    int i = 0;
    while (i < code.length()) {
      char c = code.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (code.startsWith("/*", i)) {
        int end = code.indexOf("*/", i + 2);
        if (end < 0) {
          throw new IllegalArgumentException("unterminated comment");
        }
        String comment = code.substring(i + 2, end);
        if (!comment.startsWith("[[") || !comment.endsWith("]]")) {
          throw new IllegalArgumentException("a comment that is not a Thymeleaf value");
        }
        i = end + 2;
      } else if (code.startsWith("//", i)) {
        throw new IllegalArgumentException("a line comment");
      } else if (c == '\'' || c == '"') {
        int j = i + 1;
        while (j < code.length() && code.charAt(j) != c) {
          j += code.charAt(j) == '\\' ? 2 : 1;
        }
        String literal = code.substring(i, Math.min(j + 1, code.length()));
        if (literal.contains("[[") || literal.contains("[(")) {
          throw new IllegalArgumentException(
              "a Thymeleaf expression inside a string literal: " + literal);
        }
        tokens.add(literal);
        i = j + 1;
      } else if (Character.isJavaIdentifierStart(c)) {
        int j = i;
        while (j < code.length() && Character.isJavaIdentifierPart(code.charAt(j))) {
          j++;
        }
        tokens.add(code.substring(i, j));
        i = j;
      } else if (Character.isDigit(c)) {
        int j = i;
        while (j < code.length() && (Character.isDigit(code.charAt(j)) || code.charAt(j) == '.')) {
          j++;
        }
        tokens.add(code.substring(i, j));
        i = j;
      } else {
        tokens.add(String.valueOf(c));
        i++;
      }
    }
    return tokens;
  }

  /** Recursive-descent recogniser of the bootstrap grammar over the token list. */
  private static final class DataParser {
    private final List<String> tokens;
    private int at;

    DataParser(List<String> tokens) {
      this.tokens = tokens;
    }

    void program() {
      while (at < tokens.size()) {
        statement();
      }
    }

    private void statement() {
      String head = next();
      if (DECLARATIONS.contains(head)) {
        identifier();
      } else if (head.equals("window")) {
        expect(".");
        identifier();
      } else {
        throw new IllegalArgumentException("a statement that is not a declaration: " + head);
      }
      expect("=");
      value();
      if (at < tokens.size() && tokens.get(at).equals(";")) {
        at++;
      }
    }

    private void value() {
      String token = next();
      switch (token) {
        case "{" -> object();
        case "[" -> array();
        case "-" -> number(next());
        default -> {
          if (!isString(token) && !KEYWORD_VALUES.contains(token) && !isNumber(token)) {
            throw new IllegalArgumentException("a value that is not a literal: " + token);
          }
        }
      }
    }

    private void object() {
      while (!peek("}")) {
        String key = next();
        if (!isString(key) && !isNumber(key) && !isIdentifier(key)) {
          throw new IllegalArgumentException("an object key that is not a name: " + key);
        }
        expect(":");
        value();
        if (!peek("}")) {
          expect(",");
        }
      }
      at++;
    }

    private void array() {
      while (!peek("]")) {
        value();
        if (!peek("]")) {
          expect(",");
        }
      }
      at++;
    }

    private void identifier() {
      String name = next();
      if (!isIdentifier(name)) {
        throw new IllegalArgumentException("a name expected, found " + name);
      }
    }

    private void number(String token) {
      if (!isNumber(token)) {
        throw new IllegalArgumentException("a number expected after '-', found " + token);
      }
    }

    private boolean peek(String token) {
      if (at >= tokens.size()) {
        throw new IllegalArgumentException("unexpected end of script");
      }
      return tokens.get(at).equals(token);
    }

    private void expect(String token) {
      String found = next();
      if (!found.equals(token)) {
        throw new IllegalArgumentException("'" + token + "' expected, found " + found);
      }
    }

    private String next() {
      if (at >= tokens.size()) {
        throw new IllegalArgumentException("unexpected end of script");
      }
      return tokens.get(at++);
    }

    private static boolean isString(String token) {
      return token.length() >= 2
          && (token.charAt(0) == '\'' || token.charAt(0) == '"')
          && token.charAt(token.length() - 1) == token.charAt(0);
    }

    private static boolean isNumber(String token) {
      return token.matches("\\d+(\\.\\d+)?");
    }

    private static boolean isIdentifier(String token) {
      return Character.isJavaIdentifierStart(token.charAt(0))
          && !DECLARATIONS.contains(token)
          && !KEYWORD_VALUES.contains(token)
          && !token.equals("function");
    }
  }

  private static Path templatesRoot() throws URISyntaxException {
    return Paths.get(
            InlineScriptDataOnlyTest.class.getResource("/templates/bank-grants.html").toURI())
        .getParent();
  }
}
