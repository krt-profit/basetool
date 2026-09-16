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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No template may render a handle snapshot without the sentinel-aware display helper (REQ-SEC-062,
 * the root i18n rule).
 *
 * <p>A granted Art. 17 erasure replaces the member's name in these columns with {@code
 * #ANONYMISED#}, a deliberately untranslatable token. Every human-facing surface is supposed to map
 * it through {@code @handles.display(...)} to {@code general.anonymisedHandle}; a template that
 * renders the column directly prints the token verbatim, which is both a hardcoded user-visible
 * string and a worse answer than the erasure deserves.
 *
 * <p><b>Why a gate and not a careful sweep.</b> The first sweep was by hand and missed two sites in
 * a file whose two <em>other</em> sites had been wrapped — the reviewer found them by reading. This
 * change also widened the erasure to {@code bank_holder.handle} and {@code
 * audit_event.subject_label}, which added eleven more render points across five templates. A
 * hand-kept list of wrapped call sites is exactly the shape that drifts.
 *
 * <p>Presence checks are not renders: {@code th:if="${!#strings.isEmpty(r.counterpartyHandle())}"}
 * asks whether there is a value, and wrapping that would compare against the placeholder rather
 * than the column. Only the attributes that put text on the page are scanned, and within those a
 * comparison or an emptiness test is skipped — a ternary's condition is a question about the column
 * and only its branches are renders.
 */
class AnonymisedHandleRenderTest {

  /**
   * A navigation onto something whose value can be the erasure sentinel.
   *
   * <p><b>A pattern, not a set of literal strings, and both halves of it are a correction.</b> The
   * first version of this gate held {@code Set.of("handle()", "actorHandle()", …)} and matched with
   * a case-sensitive {@code indexOf}, which is blind twice over: {@code ${order.handle}} contains
   * no {@code handle()} at all, and {@code "handle()"} is not a substring of {@code
   * "holderHandle()"}. Simulated over every template, that matcher flagged <b>nothing</b> while
   * nine sites were unwrapped — and two sites that <em>are</em> wrapped use property navigation, so
   * unwrapping them would have left the suite green. It never did the wrapping it appeared to pin.
   *
   * <p>So: any dotted segment whose name ends in {@code handle} in either case, or is {@code
   * subject}, with or without the call parentheses. That over-flags — a hypothetical {@code
   * hasHandle()} would match — which is the right direction for a gate whose failure mode is a name
   * nobody noticed.
   */
  private static final Pattern SNAPSHOT_ACCESSOR =
      Pattern.compile("\\.(\\w*[Hh]andle|subject)(\\(\\))?\\b");

  /**
   * A message-bundle lookup, which is a key and not an expression.
   *
   * <p>{@code #{orders.create.handle}} and {@code #{admin.audit.col.subject}} are column captions.
   * They match the accessor pattern and are not renders of anything, so they are cut out before the
   * scan rather than exempted one by one.
   */
  private static final Pattern MESSAGE_LOOKUP = Pattern.compile("#\\{[^}]*\\}");

  /** The attributes that put a value on the page as text. */
  private static final Pattern RENDERING_ATTRIBUTE =
      Pattern.compile("th:(?:text|utext|title)=\"([^\"]*)\"");

  /** What every occurrence inside such an attribute has to be wrapped in. */
  private static final String WRAPPER = "@handles.display(";

  // covers REQ-SEC-062 - an erased handle must never reach a page as its raw token
  @Test
  void everyRenderedHandleGoesThroughTheDisplayHelper() throws IOException {
    List<String> unwrapped = new ArrayList<>();

    for (Path template : templates()) {
      String content = Files.readString(template, StandardCharsets.UTF_8);
      Matcher attribute = RENDERING_ATTRIBUTE.matcher(content);
      while (attribute.find()) {
        // Blanked rather than removed, so every offset below still addresses the same character.
        String expression = MESSAGE_LOOKUP.matcher(attribute.group(1)).replaceAll(this::blanked);
        Matcher accessor = SNAPSHOT_ACCESSOR.matcher(expression);
        while (accessor.find()) {
          if (isPredicate(expression, accessor.start(), accessor.end())
              || isWrapped(expression, accessor.start() + 1)) {
            continue;
          }
          unwrapped.add(
              template.getFileName() + ": " + accessor.group() + " in " + oneLine(expression));
        }
      }
    }

    assertThat(unwrapped)
        .as(
            "Each of these renders a column a granted erasure rewrites to the sentinel, so the page"
                + " would print #ANONYMISED# verbatim. Wrap the expression in"
                + " ${@handles.display(...)}, which maps it to general.anonymisedHandle and passes"
                + " every other value through unchanged.")
        .isEmpty();
  }

  // covers REQ-SEC-062 - the matcher has to be able to see an unwrapped render at all
  @Test
  void theMatcherFlagsAnUnwrappedRenderInEitherSpelling() {
    // The previous anti-vacuity check counted files containing "@handles.display(" and asserted
    // "> 5", which is true of a matcher that finds nothing: it measured the templates, not the
    // scan. This exercises the matcher against both spellings it used to be blind to.
    assertThat(findUnwrapped("th:text=\"${order.handle}\""))
        .as("property navigation, the spelling two already-wrapped sites use")
        .isNotEmpty();
    assertThat(findUnwrapped("th:text=\"${b.holderHandle()}\""))
        .as("a capitalised suffix, which a case-sensitive indexOf of \"handle()\" misses")
        .isNotEmpty();
    assertThat(findUnwrapped("th:text=\"${@handles.display(order.handle)}\""))
        .as("and the wrapped form is not flagged")
        .isEmpty();
    assertThat(findUnwrapped("th:text=\"#{orders.create.handle}\""))
        .as("a message key is a caption, not a render")
        .isEmpty();
    assertThat(findUnwrapped("th:if=\"${r.deciderHandle() != null}\""))
        .as("and a presence check is not a render either")
        .isEmpty();
  }

  /**
   * Runs the scan over one fragment of markup.
   *
   * @param markup the attribute to scan
   * @return the findings, empty when the fragment is clean
   */
  private List<String> findUnwrapped(String markup) {
    List<String> out = new ArrayList<>();
    Matcher attribute = RENDERING_ATTRIBUTE.matcher(markup);
    while (attribute.find()) {
      String expression = MESSAGE_LOOKUP.matcher(attribute.group(1)).replaceAll(this::blanked);
      Matcher accessor = SNAPSHOT_ACCESSOR.matcher(expression);
      while (accessor.find()) {
        if (isPredicate(expression, accessor.start(), accessor.end())
            || isWrapped(expression, accessor.start() + 1)) {
          continue;
        }
        out.add(accessor.group());
      }
    }
    return out;
  }

  /**
   * As many spaces as the match was long.
   *
   * @param match the message lookup to blank out
   * @return the replacement
   */
  private String blanked(MatchResult match) {
    return " ".repeat(match.group().length());
  }

  /**
   * Whether the occurrence at this position is a question about the value rather than a render.
   *
   * <p>A ternary in a {@code th:text} routinely tests the column and renders it in one branch:
   * {@code ${r.deciderHandle() != null} ? ${@handles.display(r.deciderHandle())} : '-'}. The test
   * must not be wrapped — comparing the display placeholder against {@code null} would answer the
   * wrong question — so only the branch counts.
   *
   * @param expression the whole attribute value
   * @param at the index of the accessor's leading dot
   * @param end the index just past the accessor
   * @return {@code true} when this occurrence feeds a comparison or an emptiness test
   */
  private static boolean isPredicate(String expression, int at, int end) {
    // The trailing "()" may sit outside the match: the pattern's word boundary cannot hold between
    // ")" and a space, so it backtracks and the call parentheses are left for us to step over.
    String after = expression.substring(end).replaceFirst("^\\(\\)", "").stripLeading();
    if (after.startsWith("!=") || after.startsWith("==")) {
      return true;
    }
    String before = expression.substring(0, at);
    return before.endsWith("#strings.isEmpty(")
        || before.endsWith("#lists.isEmpty(")
        || before.contains("#strings.isEmpty(")
            && before.lastIndexOf('(') > before.lastIndexOf(')');
  }

  /**
   * Whether the occurrence at this position sits inside the display helper's parentheses.
   *
   * <p>Looks backwards for the wrapper rather than forwards for a closing bracket: the expression
   * between the two is always a single navigation like {@code r.counterpartyHandle()}, so the
   * helper's opening parenthesis is the nearest thing to the left.
   *
   * @param expression the whole attribute value
   * @param at the index of the accessor
   * @return {@code true} when it is wrapped
   */
  private static boolean isWrapped(String expression, int at) {
    int wrapper = expression.lastIndexOf(WRAPPER, at);
    if (wrapper < 0) {
      return false;
    }
    // Nothing but the navigation may sit between the wrapper and the accessor, or the wrapper
    // belongs to an earlier occurrence in the same expression.
    String between = expression.substring(wrapper + WRAPPER.length(), at);
    return between.matches("[A-Za-z0-9_.\\[\\]]*");
  }

  /**
   * Every Thymeleaf template in the module.
   *
   * @return the template paths
   * @throws IOException when the tree cannot be walked
   */
  private static List<Path> templates() throws IOException {
    Path root = resolveModuleRelative("src/main/resources/templates");
    assertThat(Files.isDirectory(root))
        .as("template root not found at " + root.toAbsolutePath())
        .isTrue();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(p -> p.toString().endsWith(".html")).sorted().toList();
    }
  }

  /**
   * Reads a template, turning the checked exception into an unchecked one for stream use.
   *
   * @param template the file
   * @return its content
   */
  private static String read(Path template) {
    try {
      return Files.readString(template, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Collapses an expression onto one line so a failure message stays readable.
   *
   * @param expression the attribute value
   * @return the same, whitespace-squashed and truncated
   */
  private static String oneLine(String expression) {
    String squashed = expression.replaceAll("\\s+", " ").trim();
    return squashed.length() <= 120 ? squashed : squashed.substring(0, 117) + "...";
  }

  /**
   * Resolves a module-relative path, tolerating a run from the repository root.
   *
   * @param relative the module-relative path
   * @return the path that exists, or the module-relative one when neither does
   */
  private static Path resolveModuleRelative(String relative) {
    Path direct = Paths.get(relative);
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromRepoRoot = Paths.get("frontend").resolve(relative);
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return direct;
  }
}
