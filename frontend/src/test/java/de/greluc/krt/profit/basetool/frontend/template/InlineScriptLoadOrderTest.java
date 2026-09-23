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

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The structural half of the deferred load order (FE-PERF-05, REQ-FE-023).
 *
 * <p>Every external script is {@code defer} — the head's shared ones and every page module — so
 * they run in document order after parsing, and the page modules still run after the head scripts
 * they depend on. The one exception is {@code krt-client-error.js}, which must run first and
 * synchronously: it installs the error handlers that observe every other script.
 *
 * <p>What that makes dangerous is an <em>inline</em> script: it still runs where it stands, during
 * parsing, before any deferred file. A top-level {@code bindSwaps()} or {@code (function () {…})()}
 * that touches {@code window.krtFetch} therefore meets an undefined global, and the {@code if
 * (window.krtFetch)} guard those scripts carry turns it into a silent no-op — the exact shape this
 * load order has regressed in three times. So an inline script in a page may only declare
 * (constants, functions, {@code window.*} dictionaries) and register listeners at its top level;
 * anything it has to <em>run</em> goes into a {@code DOMContentLoaded} listener, which fires after
 * every deferred script.
 *
 * <p>{@code fragments/head.html} is exempt from the second rule by design: its inline scripts are
 * the bootstrap stubs and dictionaries that must exist before anything else, and they call nothing
 * but their own code. {@code ScriptLoadOrderE2eTest} is the runtime half.
 */
class InlineScriptLoadOrderTest {

  /** The one script that must stay synchronous and first. */
  private static final String CLIENT_ERROR_BEACON = "/js/krt-client-error.js";

  /**
   * Calls an inline script may make at its top level: looking up the elements already parsed above
   * it, registering listeners and {@code krtEvents} handlers (the head stub queues those), and the
   * statement keywords the call pattern also matches. Anything else — a local function, an
   * immediately invoked one — runs code, and that code may meet a global that does not exist yet.
   */
  private static final Set<String> ALLOWED_TOP_LEVEL_CALLS =
      Set.of(
          "document.addEventListener",
          "window.addEventListener",
          "document.getElementById",
          "document.querySelector",
          "document.querySelectorAll",
          "window.krtEvents.on",
          "Object.freeze",
          "Object.assign",
          "function",
          "if",
          "for",
          "while",
          "switch",
          "catch",
          "return",
          "typeof");

  private static final Pattern SCRIPT =
      Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

  private static final Pattern CALL =
      Pattern.compile("(?<![\\w$.])((?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*)\\s*\\(");

  private static final Pattern IIFE = Pattern.compile("\\)\\s*\\(");

  @Test
  void everyExternalScriptIsDeferredExceptTheBeacon() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      String html = stripComments(Files.readString(template, StandardCharsets.UTF_8));
      Matcher script = SCRIPT.matcher(html);
      while (script.find()) {
        String attrs = script.group(1);
        if (!attrs.contains("th:src=") && !attrs.contains(" src=")) {
          continue;
        }
        boolean deferred = attrs.matches("(?s).*\\b(defer|async)\\b.*");
        boolean beacon = attrs.contains(CLIENT_ERROR_BEACON);
        if (beacon == deferred) {
          offenders.add(template.getFileName() + " -> <script" + attrs + ">");
        }
      }
    }
    assertThat(offenders)
        .as(
            "every external script is defer (FE-PERF-05), except krt-client-error.js, which must"
                + " run first and synchronously")
        .isEmpty();
  }

  @Test
  void theBeaconIsTheFirstScriptOfTheHead() throws IOException, URISyntaxException {
    String head =
        stripComments(
            Files.readString(
                templatesRoot().resolve("fragments/head.html"), StandardCharsets.UTF_8));
    Matcher first = SCRIPT.matcher(head);
    assertThat(first.find()).as("head.html has scripts").isTrue();
    assertThat(first.group(1)).contains(CLIENT_ERROR_BEACON).doesNotContain("defer");
  }

  @Test
  void noInlinePageScriptRunsCodeAtParseTime() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      if (template.endsWith(Paths.get("fragments", "head.html"))) {
        continue;
      }
      String html = stripComments(Files.readString(template, StandardCharsets.UTF_8));
      Matcher script = SCRIPT.matcher(html);
      while (script.find()) {
        if (script.group(1).contains("src=")) {
          continue;
        }
        for (String call : topLevelCalls(script.group(2))) {
          offenders.add(template.getFileName() + " -> " + call);
        }
      }
    }
    assertThat(offenders)
        .as(
            "an inline script runs before every deferred file; wrap what it runs in a"
                + " DOMContentLoaded listener (FE-PERF-05)")
        .isEmpty();
  }

  @Test
  void theCheckSeesATopLevelCallAndAnIife() {
    // The guard's own sanity check against the exact shapes it replaced.
    assertThat(topLevelCalls("function bind() { window.krtFetch.bindSwap({}); }\nbind();"))
        .containsExactly("bind");
    assertThat(topLevelCalls("(function () { if (!window.krtFetch) { return; } })();"))
        .containsExactly("(IIFE)");
    assertThat(
            topLevelCalls(
                "const A = /*[[#{x}]]*/ 'a';\ndocument.addEventListener('DOMContentLoaded',"
                    + " function () { bind(); });"))
        .isEmpty();
  }

  /**
   * The calls an inline script makes at brace depth zero, outside every function body, that are not
   * in {@link #ALLOWED_TOP_LEVEL_CALLS}; an immediately invoked function is reported as {@code
   * (IIFE)}.
   *
   * @param code the inline script's source
   * @return the offending calls, in source order
   */
  static List<String> topLevelCalls(String code) {
    String top = depthZero(stripJs(code));
    List<String> calls = new ArrayList<>();
    Matcher call = CALL.matcher(top);
    while (call.find()) {
      boolean declaration = top.substring(0, call.start()).stripTrailing().endsWith("function");
      String name = call.group(1);
      boolean listener = name.endsWith(".addEventListener");
      if (!declaration && !listener && !ALLOWED_TOP_LEVEL_CALLS.contains(name)) {
        calls.add(name);
      }
    }
    if (IIFE.matcher(top).find()) {
      calls.add("(IIFE)");
    }
    return calls;
  }

  /**
   * Blanks comments and string and template literals out of JavaScript source, so braces and
   * parentheses inside them count for nothing.
   *
   * @param code the source
   * @return the source with every comment removed and every literal reduced to {@code ''}
   */
  private static String stripJs(String code) {
    StringBuilder out = new StringBuilder(code.length());
    int i = 0;
    while (i < code.length()) {
      char c = code.charAt(i);
      if (code.startsWith("/*", i)) {
        int end = code.indexOf("*/", i + 2);
        i = end < 0 ? code.length() : end + 2;
        out.append(' ');
      } else if (code.startsWith("//", i)) {
        int end = code.indexOf('\n', i);
        i = end < 0 ? code.length() : end;
      } else if (c == '\'' || c == '"' || c == '`') {
        int j = i + 1;
        while (j < code.length() && code.charAt(j) != c) {
          j += code.charAt(j) == '\\' ? 2 : 1;
        }
        out.append("''");
        i = j + 1;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Keeps only the characters at brace depth zero; everything inside a block becomes a space.
   *
   * @param code comment- and literal-free source
   * @return the depth-zero text
   */
  private static String depthZero(String code) {
    StringBuilder top = new StringBuilder(code.length());
    int depth = 0;
    for (char c : code.toCharArray()) {
      if (c == '{') {
        depth++;
        top.append(' ');
      } else if (c == '}') {
        depth--;
        top.append(' ');
      } else {
        top.append(depth == 0 ? c : ' ');
      }
    }
    return top.toString();
  }

  private static String stripComments(String html) {
    return html.replaceAll("(?s)<!--.*?-->", "");
  }

  private static Path templatesRoot() throws URISyntaxException {
    return Paths.get(
            InlineScriptLoadOrderTest.class.getResource("/templates/bank-grants.html").toURI())
        .getParent();
  }

  private static List<Path> templates() throws IOException, URISyntaxException {
    try (Stream<Path> tree = Files.walk(templatesRoot())) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".html"))
          .sorted()
          .toList();
    }
  }
}
