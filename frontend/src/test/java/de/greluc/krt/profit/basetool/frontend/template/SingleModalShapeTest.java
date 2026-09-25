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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time enforcement of REQ-UI-013 / ADR-0177: the app has <strong>exactly one</strong> dialog
 * shape, {@code .krt-modal-overlay} &gt; {@code .krt-modal}.
 *
 * <p>Until #1891 there were three. The canonical shape, legacy A ({@code .modal} / {@code
 * .modal-content}) and legacy B ({@code .modal-overlay} / {@code .modal-box}) differed in their
 * height cap, their body scrolling and — worst — in how they were opened, legacy A's root
 * defaulting to <em>visible</em> where the canonical one defaults to hidden. Every dialog defect
 * therefore had to be fixed once per shape, and a behaviour keyed on the root class was silently
 * absent from the shapes nobody remembered: {@code krt-live-sync.js}'s "is any dialog open?" guard
 * queried {@code .krt-modal-overlay} only, so 54 of 96 dialogs could be swapped out from under an
 * open form.
 *
 * <p>That is what this test exists to prevent recurring. A fourth shape is not reported by a
 * hand-maintained list of shapes — it is silently not measured — so the assertion is inverted: no
 * legacy dialog class may appear <em>anywhere</em> in the templates or the hand-written
 * stylesheets, and every dialog root must be the canonical one. A new shape fails the build on the
 * first commit that introduces it rather than on the second defect that has to be fixed twice.
 */
class SingleModalShapeTest {

  /**
   * The dialog classes that must never come back. Each is matched as a whole class token, so {@code
   * .krt-modal-overlay} and {@code close-modal-display} are not false positives.
   */
  private static final List<String> FORBIDDEN_CLASSES =
      List.of(
          "modal",
          "modal-content",
          "modal-overlay",
          "modal-box",
          "modal-title",
          "modal-actions",
          "close-modal");

  /**
   * The shell's own classes, which only {@code fragments/modal-wrapper.html} may carry. A page's
   * dialog supplies {@code .krt-modal-body} and {@code .krt-modal-foot}, never these.
   */
  private static final List<String> SHELL_CLASSES =
      List.of("krt-modal-overlay", "krt-modal", "krt-modal-head", "krt-modal-close");

  /**
   * Matches a class token exactly: not preceded or followed by a word character or a hyphen. That
   * is what separates the legacy {@code modal} from the canonical {@code krt-modal}, and the legacy
   * {@code close-modal} from the shared {@code close-modal-display} trigger name.
   */
  private static Pattern classToken(String name) {
    return Pattern.compile("(?<![-\\w])" + Pattern.quote(name) + "(?![-\\w])");
  }

  /**
   * Asserts that no template declares an element carrying one of the legacy dialog classes.
   *
   * <p>Only {@code class="…"} attribute values are inspected, so prose in an HTML comment may still
   * name a legacy shape to explain the history — which several templates and this change's own
   * comments deliberately do.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void noTemplateCarriesALegacyDialogClass() throws IOException, URISyntaxException {
    Pattern classAttribute = Pattern.compile("class=\"([^\"]*)\"");
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      String html = Files.readString(template, StandardCharsets.UTF_8);
      Matcher attribute = classAttribute.matcher(html);
      while (attribute.find()) {
        String value = attribute.group(1);
        for (String forbidden : FORBIDDEN_CLASSES) {
          if (classToken(forbidden).matcher(value).find()) {
            offenders.add(template.getFileName() + " -> class=\"" + value + "\"");
          }
        }
      }
    }
    assertThat(offenders)
        .as(
            "REQ-UI-013/ADR-0177: exactly one dialog shape. Use .krt-modal-overlay > .krt-modal"
                + " with .krt-modal-head / .krt-modal-body / .krt-modal-foot")
        .isEmpty();
  }

  /**
   * Asserts that no hand-written stylesheet declares a rule for a legacy dialog class.
   *
   * <p>The markup check alone would pass against dead CSS, and dead CSS for a deleted shape is
   * exactly the invitation to bring the shape back. {@code inline-migration.css} is generated but
   * still checked — a regenerated legacy utility is the same defect.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the CSS classpath root cannot be resolved
   */
  @Test
  void noStylesheetDeclaresALegacyDialogRule() throws IOException, URISyntaxException {
    // Selectors only: the rule bodies and the comments above them may name a legacy shape, and
    // styles.css deliberately does so to record why .krt-modal-close now carries its own rule.
    Pattern selectorLine = Pattern.compile("^([^{}/*\\n][^{}\\n]*)\\{", Pattern.MULTILINE);
    List<String> offenders = new ArrayList<>();
    for (Path sheet : stylesheets()) {
      String css = Files.readString(sheet, StandardCharsets.UTF_8);
      Matcher selector = selectorLine.matcher(css);
      while (selector.find()) {
        String text = selector.group(1);
        for (String forbidden : FORBIDDEN_CLASSES) {
          if (classToken(forbidden).matcher(text).find() && text.contains("." + forbidden)) {
            offenders.add(sheet.getFileName() + " -> " + text.trim());
          }
        }
      }
    }
    assertThat(offenders)
        .as("the legacy dialog shapes are deleted; no stylesheet may declare their rules")
        .isEmpty();
  }

  /**
   * Asserts the positive half: every dialog is rendered by {@code fragments/modal-wrapper.html}
   * (2026-09-23), and that fragment draws the canonical shell.
   *
   * <p>Until then 90 of the 96 dialogs wrote the shell by hand, and it had drifted: {@code <h2>} in
   * 76 and {@code <h3>} in 20, an ✕ in 74 and an icon button in 22, the accessible name on the
   * overlay in some and on the frame in others, and five close controls on the order page that
   * closed nothing. So no template but the wrapper may carry the shell's own classes — the page
   * supplies only {@code .krt-modal-body} and {@code .krt-modal-foot} — and the wrapper must be
   * called often enough that the check is still measuring something.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void everyDialogIsRenderedByTheWrapper() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    int calls = 0;
    String wrapper = null;
    for (Path template : templates()) {
      String html =
          Files.readString(template, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
      if (template.endsWith(Paths.get("fragments", "modal-wrapper.html"))) {
        wrapper = html;
        continue;
      }
      calls += countOccurrences(html, "fragments/modal-wrapper :: modal(");
      for (String shell : SHELL_CLASSES) {
        if (count(html, shell) > 0) {
          offenders.add(template.getFileName() + " -> a hand-written ." + shell);
        }
      }
    }
    assertThat(offenders)
        .as("REQ-UI-013/ADR-0177: every dialog is rendered by fragments/modal-wrapper :: modal")
        .isEmpty();
    assertThat(calls).as("the app still renders its dialogs through the wrapper").isGreaterThan(90);
    assertThat(wrapper).as("fragments/modal-wrapper.html").isNotNull();
    for (String shell : SHELL_CLASSES) {
      assertThat(count(wrapper, shell)).as("the wrapper draws one .%s", shell).isEqualTo(1);
    }
    assertThat(wrapper)
        .as("the design system's head: an <h2> title and the ✕ close glyph")
        .contains("<h2 ")
        .doesNotContain("<h3")
        .contains("&#10005;</button>");
  }

  /**
   * Asserts that every canonical overlay is a native {@code <dialog>} (FE-SIMP-04b, ADR-0177).
   *
   * <p>{@code window.krtModal.open} shows an overlay with {@code showModal()}: top layer, the page
   * behind it inert, Escape raised as {@code cancel}. A {@code .krt-modal-overlay} left as a {@code
   * <div>} would still open — by its class — but without any of that, so the app would be back to
   * two dialog behaviours under one class name, which is the split this test exists to prevent.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void everyOverlayIsANativeDialog() throws IOException, URISyntaxException {
    Pattern overlayTag =
        Pattern.compile(
            "<(\\w+)\\b[^>]*class=\"[^\"]*(?<![-\\w])krt-modal-overlay(?![-\\w])[^\"]*\"");
    List<String> offenders = new ArrayList<>();
    int dialogs = 0;
    for (Path template : templates()) {
      String html =
          Files.readString(template, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
      Matcher tag = overlayTag.matcher(html);
      while (tag.find()) {
        if ("dialog".equals(tag.group(1))) {
          dialogs++;
        } else {
          offenders.add(
              template.getFileName() + " -> <" + tag.group(1) + " class=krt-modal-overlay>");
        }
      }
    }
    assertThat(offenders).as("every .krt-modal-overlay is a <dialog>").isEmpty();
    assertThat(dialogs)
        .as("exactly one overlay tag exists — the wrapper's root — and it is a <dialog>")
        .isEqualTo(1);
  }

  /**
   * Asserts that a dialog's submit button is inside the {@code <form>} it submits.
   *
   * <p>The canonical shell puts the actions in a {@code .krt-modal-foot} that is a sibling of
   * {@code .krt-modal-body}, so a {@code <form>} wrapping the fields has to wrap <em>both</em> or
   * the submit button ends up outside it. A submit button outside its form is not a layout problem:
   * clicking it does nothing at all, silently. The port shipped exactly that defect twice — the
   * Auftrag edit dialog and the Lager bulk-rebook dialog — and only one of them had an e2e test to
   * catch it, which is why the invariant is asserted here for all of them.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void everySubmitButtonSitsInsideItsForm() throws IOException, URISyntaxException {
    Pattern token = Pattern.compile("<form\\b[^>]*>|</form>|type=\"submit\"");
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      String html = Files.readString(template, StandardCharsets.UTF_8);
      for (String dialog : dialogBlocks(html)) {
        int depth = 0;
        Matcher match = token.matcher(dialog);
        while (match.find()) {
          String found = match.group();
          if (found.startsWith("<form")) {
            depth++;
          } else if ("</form>".equals(found)) {
            depth--;
          } else if (depth == 0 && dialog.contains("<form")) {
            offenders.add(template.getFileName() + " -> a submit button outside its <form>");
            break;
          }
        }
      }
    }
    assertThat(offenders)
        .as("a .krt-modal-foot submit button must be inside the <form> it submits")
        .isEmpty();
  }

  /**
   * Splits a template into the source text of each dialog: every call of the wrapper, from the
   * start tag that carries it ({@code <th:block>} or {@code <div>}) to that element's closing tag,
   * which encloses the body the call passes in.
   *
   * @param html the template source
   * @return one string per dialog found
   */
  private static List<String> dialogBlocks(String html) {
    Pattern call =
        Pattern.compile(
            "<(th:block|div)\\b[^>]*th:replace=\"~\\{fragments/modal-wrapper :: modal\\(");
    List<String> blocks = new ArrayList<>();
    Matcher start = call.matcher(html);
    while (start.find()) {
      String name = Pattern.quote(start.group(1));
      Pattern tags = Pattern.compile("<" + name + "\\b[^>]*?(/)?>|</" + name + ">");
      int depth = 0;
      Matcher walk = tags.matcher(html);
      walk.region(start.start(), html.length());
      while (walk.find()) {
        String tag = walk.group();
        if (tag.startsWith("</")) {
          depth--;
          if (depth == 0) {
            blocks.add(html.substring(start.start(), walk.end()));
            break;
          }
        } else if (walk.group(1) == null) {
          depth++;
        }
      }
    }
    return blocks;
  }

  /** Counts the literal occurrences of {@code needle} in {@code haystack}. */
  private static int countOccurrences(String haystack, String needle) {
    int hits = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      hits++;
    }
    return hits;
  }

  /** Counts whole-token occurrences of a class name inside {@code class="…"} attributes. */
  private static int count(String html, String name) {
    Pattern classAttribute = Pattern.compile("class=\"([^\"]*)\"");
    Matcher attribute = classAttribute.matcher(html);
    Pattern token = classToken(name);
    int hits = 0;
    while (attribute.find()) {
      if (token.matcher(attribute.group(1)).find()) {
        hits++;
      }
    }
    return hits;
  }

  /**
   * Resolves every Thymeleaf template on the test classpath.
   *
   * <p>Anchors on a known file rather than the bare {@code /templates} directory resource, which
   * {@code getResource} does not resolve portably — the same approach {@code
   * LiveSyncSectionMapParityTest} uses.
   *
   * @return every {@code .html} file under the templates root, subdirectories included
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  private static List<Path> templates() throws IOException, URISyntaxException {
    return filesUnder("/templates/bank-grants.html", ".html");
  }

  /**
   * Resolves every stylesheet on the test classpath.
   *
   * @return every {@code .css} file under the static CSS root
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  private static List<Path> stylesheets() throws IOException, URISyntaxException {
    return filesUnder("/static/css/styles.css", ".css");
  }

  /**
   * Walks the directory holding {@code anchorResource} and returns every file with {@code
   * extension}.
   *
   * @param anchorResource a classpath resource whose parent directory is the root to walk
   * @param extension the file suffix to keep
   * @return the matching files, sorted for a stable failure message
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the anchor cannot be resolved to a path
   */
  private static List<Path> filesUnder(String anchorResource, String extension)
      throws IOException, URISyntaxException {
    URL anchor = SingleModalShapeTest.class.getResource(anchorResource);
    assertThat(anchor).as("%s classpath resource", anchorResource).isNotNull();
    Path root = Paths.get(anchor.toURI()).getParent();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(extension))
          .sorted()
          .toList();
    }
  }
}
