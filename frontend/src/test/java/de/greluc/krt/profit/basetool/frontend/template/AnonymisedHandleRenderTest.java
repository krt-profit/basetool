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
import java.util.Set;
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

  /** The accessors whose value can be the erasure sentinel. */
  private static final Set<String> SNAPSHOT_ACCESSORS =
      Set.of(
          "handle()",
          "actorHandle()",
          "recipientHandle()",
          "counterpartyHandle()",
          "requesterHandle()",
          "deciderHandle()",
          "subject()");

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
        String expression = attribute.group(1);
        for (String accessor : SNAPSHOT_ACCESSORS) {
          int at = expression.indexOf(accessor);
          while (at >= 0) {
            if (!isPredicate(expression, at) && !isWrapped(expression, at)) {
              unwrapped.add(
                  template.getFileName() + ": " + accessor + " in " + oneLine(expression));
            }
            at = expression.indexOf(accessor, at + accessor.length());
          }
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

  // covers REQ-SEC-062 - the scan has to actually be looking at something
  @Test
  void theScanCoversTheTemplatesThatRenderHandles() throws IOException {
    long wrapped =
        templates().stream()
            .map(AnonymisedHandleRenderTest::read)
            .filter(content -> content.contains(WRAPPER))
            .count();

    assertThat(wrapped)
        .as(
            "templates that wrap at least one handle -- if this drops to zero the pattern was"
                + " renamed and the assertion above has become vacuous")
        .isGreaterThan(5);
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
   * @param at the index of the accessor
   * @return {@code true} when this occurrence feeds a comparison or an emptiness test
   */
  private static boolean isPredicate(String expression, int at) {
    String after = expression.substring(at).replaceFirst("^[A-Za-z0-9_()]*", "").stripLeading();
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
