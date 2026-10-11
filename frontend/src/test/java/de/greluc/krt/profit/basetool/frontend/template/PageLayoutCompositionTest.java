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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Keeps every page template composed through {@code fragments/layout :: page} (REQ-FE-033): no page
 * template calls the head, sidebar, header or toast fragment itself, and every template outside
 * {@code fragments/} that has a {@code <head>} is a layout call. A page that copies the chrome
 * again fails here before the page chrome snapshot has to say so.
 */
class PageLayoutCompositionTest {

  /** The page-level call of a chrome fragment, which only the layout may make. */
  private static final Pattern CHROME_CALL =
      Pattern.compile("fragments/(?:head|sidebar|header|toast)\\s*::");

  /** The call of the layout fragment. */
  private static final Pattern LAYOUT_CALL =
      Pattern.compile("<html\\b[^>]*th:replace=\"~\\{fragments/layout\\s*::\\s*page\\(");

  /** A {@code <head>} element, which marks a template as a page. */
  private static final Pattern HEAD = Pattern.compile("<head\\b");

  /** The floor of page templates, so a vanished scan cannot pass. */
  private static final int PAGE_FLOOR = 90;

  @Test
  void everyPageIsComposedThroughTheLayout() throws IOException {
    Map<String, String> templates = TemplateReferenceScan.templates();
    List<String> offenders = new ArrayList<>();
    int pages = 0;
    for (Map.Entry<String, String> template : templates.entrySet()) {
      String name = template.getKey();
      if (name.startsWith("fragments/")) {
        continue;
      }
      List<String> problems = problems(template.getValue());
      if (HEAD.matcher(template.getValue()).find()) {
        pages++;
      }
      problems.forEach(problem -> offenders.add(name + ": " + problem));
    }
    assertThat(pages).as("page templates with a head").isGreaterThanOrEqualTo(PAGE_FLOOR);
    assertThat(offenders)
        .as(
            "a page hands fragments/layout :: page its title, links, content and tail; it never"
                + " calls the head, sidebar, header or toast itself")
        .isEmpty();
  }

  @Test
  void aPageThatCopiesTheChromeIsReported() {
    String legacy =
        "<html xmlns:th=\"http://www.thymeleaf.org\"><head th:replace=\"~{fragments/head ::"
            + " head(titleContent=~{::title}, additionalLinks=~{})}\"><title>x</title></head><body>"
            + "<th:block th:replace=\"~{fragments/sidebar :: sidebar}\" /></body></html>";

    assertThat(problems(legacy)).hasSize(2);
  }

  @Test
  void aHeadWithoutTheLayoutCallIsReported() {
    String page = "<html><head><title>x</title></head><body></body></html>";

    assertThat(problems(page)).containsExactly("has a <head> but no fragments/layout :: page call");
  }

  @Test
  void aPageThroughTheLayoutHasNoProblem() {
    String page =
        "<html th:replace=\"~{fragments/layout :: page(pageTitle=~{::title},"
            + " pageContent=~{::pageContent})}\"><head><title>x</title></head><body>"
            + "<th:block th:fragment=\"pageContent\"></th:block></body></html>";

    assertThat(problems(page)).isEmpty();
  }

  @Test
  void theLayoutCallsEachChromeFragmentOnce() throws IOException {
    Path layout = TemplateReferenceScan.TEMPLATE_ROOT.resolve("fragments/layout.html");
    String source = Files.readString(layout, StandardCharsets.UTF_8);

    Map<String, Integer> calls = new TreeMap<>();
    for (String fragment : List.of("head", "sidebar", "header", "toast")) {
      calls.put(fragment, source.split("fragments/" + fragment + "\\s*::", -1).length - 1);
    }

    assertThat(calls).containsEntry("head", 1).containsEntry("sidebar", 1);
    assertThat(calls).containsEntry("header", 1).containsEntry("toast", 1);
  }

  private static @NotNull List<String> problems(@NotNull String source) {
    List<String> problems = new ArrayList<>();
    if (CHROME_CALL.matcher(source).find()) {
      problems.add("calls a chrome fragment itself");
    }
    if (HEAD.matcher(source).find() && !LAYOUT_CALL.matcher(source).find()) {
      problems.add("has a <head> but no fragments/layout :: page call");
    }
    return problems;
  }
}
