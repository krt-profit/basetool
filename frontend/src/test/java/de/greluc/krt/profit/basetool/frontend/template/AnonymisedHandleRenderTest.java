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
 * No template may render a handle snapshot without {@code @handles.display(...)}, which maps the
 * erasure sentinel {@code #ANONYMISED#} to {@code general.anonymisedHandle} (REQ-SEC-062).
 *
 * <p>Only text-rendering attributes are scanned; comparisons and emptiness tests of the column are
 * skipped, and in a ternary only the branches count as renders.
 */
class AnonymisedHandleRenderTest {

  /**
   * Matches a navigation onto a value that can be the erasure sentinel: any dotted segment ending
   * in {@code handle} in either case, or {@code subject}, with or without call parentheses. It
   * deliberately over-matches rather than miss a site.
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

  /**
   * The attribute forms Thymeleaf evaluates in restricted mode, where a bean reference throws:
   * {@code th:attr}, {@code th:attrappend}, {@code th:attrprepend} and {@code th:data-*}. Group 1
   * is the attribute, group 2 the expression text.
   */
  private static final Pattern RESTRICTED_ATTRIBUTE =
      Pattern.compile("th:(attr|attrappend|attrprepend|data-[\\w-]+)=\"([^\"]*)\"");

  @Test
  void everyRenderedHandleGoesThroughTheDisplayHelper() throws IOException {
    List<String> unwrapped = new ArrayList<>();

    for (Path template : templates()) {
      String content = Files.readString(template, StandardCharsets.UTF_8);
      Matcher attribute = RENDERING_ATTRIBUTE.matcher(content);
      while (attribute.find()) {
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

  /**
   * The display helper may not appear in an attribute evaluated in restricted mode, where the bean
   * call throws at render time; such sites must bind the value with {@code th:with} instead.
   */
  @Test
  void theWrapperNeverSitsInARestrictedAttribute() throws IOException {
    List<String> restricted = new ArrayList<>();

    for (Path template : templates()) {
      String content = Files.readString(template, StandardCharsets.UTF_8);
      Matcher attribute = RESTRICTED_ATTRIBUTE.matcher(content);
      while (attribute.find()) {
        if (attribute.group(2).contains(WRAPPER)) {
          restricted.add(
              template.getFileName()
                  + ": th:"
                  + attribute.group(1)
                  + " in "
                  + oneLine(attribute.group(2)));
        }
      }
    }

    assertThat(restricted)
        .as(
            "Thymeleaf evaluates th:attr and every th:<custom-attribute> in restricted mode, which"
                + " refuses a bean reference: each of these throws on the first render rather than"
                + " printing a wrong value. Bind it with th:with and reference the bound name"
                + " instead.")
        .isEmpty();
  }

  @Test
  void theMatcherFlagsAnUnwrappedRenderInEitherSpelling() {
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
   * Returns whether the occurrence at this position tests the value (a comparison or emptiness
   * check) rather than rendering it.
   *
   * @param expression the whole attribute value
   * @param at the index of the accessor's leading dot
   * @param end the index just past the accessor
   * @return {@code true} when this occurrence feeds a comparison or an emptiness test
   */
  private static boolean isPredicate(String expression, int at, int end) {
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
   * Returns whether the occurrence at this position sits inside the display helper's parentheses,
   * found by searching backwards for the helper.
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
