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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps developer text and page CSS out of every HTML response (FE-PERF-02).
 *
 * <p>Until 2026-09-23 each rendered page carried about 25 KB of HTML comments — the reasoning
 * behind the head's load order, test names, security considerations — and up to 25 KB of page CSS
 * in a {@code <style>} block, none of it cacheable, all of it sent with every navigation to a
 * {@code no-store} page. The comments became Thymeleaf parser-level comments ({@code <!--/* … *}
 * {@code /-->}), which the template engine drops at parse time, and the style blocks became {@code
 * static/css/pages/<page>.css}, linked in the same place so the cascade order did not change.
 *
 * <p>This test holds both. A plain comment is not a crash, it is a leak that nothing notices: the
 * page renders identically and the text ships. So the templates are scanned at source level:
 *
 * <ul>
 *   <li>no plain HTML comment outside a {@code script}, {@code style} or {@code textarea} element;
 *   <li>no parser-level comment whose body contains the closing star-slash pair before its end —
 *       that pair closes the block early and dumps the rest of the text into the page, which is how
 *       an attribute name once leaked out of {@code head.html};
 *   <li>no {@code <style>} element: page CSS lives in {@code static/css/pages}, where it is linted,
 *       cached and served once;
 *   <li>every {@code /css/pages/…} link names a file that exists, and every file there is linked by
 *       exactly one template — an orphan is dead CSS, a shared file couples pages that were
 *       independent.
 * </ul>
 */
class TemplateCommentHygieneTest {

  /** A token the sequential scan stops at: a comment opener or a raw-text element. */
  private static final Pattern TOKEN =
      Pattern.compile("<!--|<(script|style|textarea)\\b", Pattern.CASE_INSENSITIVE);

  /** A link to a page stylesheet, as the templates write it. */
  private static final Pattern PAGE_CSS_LINK =
      Pattern.compile("@\\{/css/pages/([A-Za-z0-9._-]+\\.css)}");

  @Test
  void noTemplateEmitsAPlainHtmlComment() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      String html = Files.readString(template, StandardCharsets.UTF_8);
      for (Comment comment : comments(html)) {
        if (!comment.body().startsWith("/*")) {
          offenders.add(template.getFileName() + ":" + comment.line());
        }
      }
    }
    assertThat(offenders)
        .as(
            "a plain <!-- --> comment is sent with every response; write developer notes as a"
                + " Thymeleaf parser-level comment <!--/* ... */--> (FE-PERF-02)")
        .isEmpty();
  }

  @Test
  void noParserLevelCommentClosesEarly() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    for (Path template : templates()) {
      String html = Files.readString(template, StandardCharsets.UTF_8);
      for (Comment comment : comments(html)) {
        String body = comment.body();
        if (body.startsWith("/*") && body.endsWith("*/")) {
          String inner = body.substring(2, body.length() - 2);
          String trimmed = inner.startsWith("/") ? inner.substring(1) : inner;
          if (trimmed.contains("*/")) {
            offenders.add(template.getFileName() + ":" + comment.line());
          }
        }
      }
    }
    assertThat(offenders)
        .as(
            "a star-slash pair inside a parser-level comment ends it early and renders the rest;"
                + " break it up as '* /'")
        .isEmpty();
  }

  @Test
  void noTemplateCarriesAStyleBlock() throws IOException, URISyntaxException {
    List<String> offenders = new ArrayList<>();
    Pattern style = Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE);
    for (Path template : templates()) {
      String html = stripComments(Files.readString(template, StandardCharsets.UTF_8));
      if (style.matcher(html).find()) {
        offenders.add(template.getFileName().toString());
      }
    }
    assertThat(offenders)
        .as(
            "page CSS lives in static/css/pages/<page>.css, linked where the block would stand"
                + " (FE-PERF-02)")
        .isEmpty();
  }

  @Test
  void everyPageStylesheetIsLinkedExactlyOnceAndExists() throws IOException, URISyntaxException {
    Path pagesDir =
        Paths.get(resource("/static/css/styles.css").toURI()).getParent().resolve("pages");
    Set<String> files = new TreeSet<>();
    try (Stream<Path> tree = Files.list(pagesDir)) {
      tree.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".css")).forEach(files::add);
    }
    List<String> linked = new ArrayList<>();
    for (Path template : templates()) {
      Matcher link = PAGE_CSS_LINK.matcher(Files.readString(template, StandardCharsets.UTF_8));
      while (link.find()) {
        linked.add(link.group(1));
      }
    }
    assertThat(files).as("the page stylesheets exist at all").hasSizeGreaterThan(40);
    assertThat(new TreeSet<>(linked)).as("every link names an existing file").isSubsetOf(files);
    assertThat(linked).as("every page stylesheet is linked exactly once").doesNotHaveDuplicates();
    assertThat(new TreeSet<>(linked)).as("no orphaned page stylesheet").isEqualTo(files);
  }

  @Test
  void theScanSeesComments() {
    String html =
        "<p>a</p><!-- plain --><script>var s = '<!-- not a comment -->';</script>"
            + "<!--/* parser level */-->";
    List<Comment> found = comments(html);
    assertThat(found).extracting(Comment::body).containsExactly(" plain ", "/* parser level */");
  }

  /**
   * One HTML comment found by {@link #comments(String)}.
   *
   * @param body the text between {@code <!--} and {@code -->}
   * @param line the 1-based line the comment opens on
   */
  private record Comment(String body, int line) {}

  /**
   * Lists the HTML comments of a template in document order, skipping the content of raw-text
   * elements. The scan is sequential, so a {@code <script>} mentioned inside a comment never opens
   * a raw-text span.
   *
   * @param html the template source
   * @return the comments found
   */
  private static List<Comment> comments(String html) {
    List<Comment> found = new ArrayList<>();
    Matcher token = TOKEN.matcher(html);
    int pos = 0;
    while (token.find(pos)) {
      if ("<!--".equals(token.group())) {
        int end = html.indexOf("-->", token.end());
        if (end < 0) {
          break;
        }
        found.add(new Comment(html.substring(token.end(), end), lineOf(html, token.start())));
        pos = end + 3;
      } else {
        Matcher close =
            Pattern.compile("</" + token.group(1) + "\\s*>", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        pos = close.find(token.end()) ? close.end() : html.length();
      }
    }
    return found;
  }

  /**
   * Removes every HTML comment from a template, so prose that names a tag is not taken for one.
   *
   * @param html the template source
   * @return the source without comments
   */
  private static String stripComments(String html) {
    return html.replaceAll("(?s)<!--.*?-->", "");
  }

  private static int lineOf(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static URL resource(String name) {
    URL url = TemplateCommentHygieneTest.class.getResource(name);
    assertThat(url).as("%s classpath resource", name).isNotNull();
    return url;
  }

  /**
   * Every Thymeleaf template on the test classpath, anchored on a known file as {@code
   * SingleModalShapeTest} does.
   *
   * @return every {@code .html} file under the templates root
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  private static List<Path> templates() throws IOException, URISyntaxException {
    Path root = Paths.get(resource("/templates/bank-grants.html").toURI()).getParent();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".html"))
          .sorted()
          .toList();
    }
  }
}
