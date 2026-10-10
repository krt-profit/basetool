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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the page-pattern fragments of REQ-UI-027 — page head, overflow menu, empty state, toolbar
 * search, segmented control and filter chips — through a test-only harness and asserts the markup
 * the design system and the scripts rely on.
 */
@SpringBootTest
class PagePatternFragmentsRenderTest {

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private String html;

  /** Renders the harness once per test with a count, a query and the {@code MINE} segment. */
  @BeforeEach
  void render() {
    Context context = new Context(Locale.GERMAN);
    context.setVariable("total", 24);
    context.setVariable("query", "quanta");
    context.setVariable("scope", "MINE");
    html = templateEngine.process("page-pattern-fragment-harness", context).replaceAll("\\s+", " ");
  }

  /**
   * The markup of one harness section, from its {@code <div id>} to the next section's.
   *
   * @param id the harness section id
   * @return that section's markup
   */
  private @NotNull String section(@NotNull String id) {
    int start = html.indexOf("<div id=\"" + id + "\">");
    assertThat(start).as("section %s rendered", id).isNotNegative();
    int end = html.indexOf("<div id=\"harness-", start + id.length() + 10);
    return end < 0 ? html.substring(start) : html.substring(start, end);
  }

  /**
   * The opening tag of the first element whose attributes contain the given fragment.
   *
   * @param markup the markup to search
   * @param attribute an attribute fragment such as {@code data-testid="x"}
   * @return the whole opening tag
   */
  private static @NotNull String tagWith(@NotNull String markup, @NotNull String attribute) {
    Matcher matcher =
        Pattern.compile("<[a-z]+[^>]*" + Pattern.quote(attribute) + "[^>]*>").matcher(markup);
    assertThat(matcher.find()).as("tag with %s", attribute).isTrue();
    return matcher.group();
  }

  /**
   * The page head carries the eyebrow as text, the title in the {@code h1}, the count chip and the
   * actions slot with its primary action and overflow menu.
   */
  @Test
  void pageHeadRendersEyebrowTitleCountAndActions() {
    String head = section("harness-page-head");
    assertThat(head).contains("class=\"page-head\"");
    assertThat(head).containsPattern("<span class=\"page-eyebrow\"[^>]*>Filter</span>");
    assertThat(head).contains("<h1>HEAD-TITLE</h1>");
    assertThat(head).containsPattern("class=\"chip chip--muted\"[^>]*>24</span>");
    assertThat(head).contains("<div class=\"page-actions\">");
    assertThat(head).contains("data-testid=\"page-head-primary\"");
  }

  /**
   * With an {@code eyebrowHref} the eyebrow is a back link, and without actions no slot renders.
   */
  @Test
  void pageHeadWithHrefRendersBackLinkAndNoEmptyActions() {
    String head = section("harness-page-head-back");
    String eyebrow = tagWith(head, "class=\"page-eyebrow\"");
    assertThat(eyebrow).startsWith("<a ").contains("href=\"/back\"");
    assertThat(head).contains("#krt-icon-arrow-left");
    assertThat(head).contains("<h1>BACK-TITLE</h1>");
    assertThat(head).doesNotContain("chip--muted").doesNotContain("page-actions");
  }

  /**
   * The overflow menu renders a labelled, collapsed toggle that controls a hidden menu panel
   * holding the slot's items.
   */
  @Test
  void overflowMenuRendersCollapsedToggleAndHiddenPanel() {
    String head = section("harness-page-head");
    String toggle = tagWith(head, "data-testid=\"overflow-menu-toggle\"");
    assertThat(toggle)
        .contains("aria-expanded=\"false\"")
        .contains("aria-haspopup=\"menu\"")
        .contains("aria-controls=\"harness-more\"")
        .contains("aria-label=\"Weitere Aktionen\"");
    String panel = tagWith(head, "class=\"overflow-menu__panel\"");
    assertThat(panel).contains("role=\"menu\"").contains(" hidden").contains("id=\"harness-more\"");
    assertThat(head).containsPattern("class=\"overflow-menu__item\"[^>]*>ITEM-A<");
  }

  /** The empty state renders title, text and the optional action link. */
  @Test
  void emptyStateRendersTitleTextAndAction() {
    String empty = section("harness-empty");
    assertThat(empty).contains("class=\"empty-state\"");
    assertThat(empty).contains("<span class=\"empty-title\">Filter</span>");
    assertThat(empty).contains("<span class=\"empty-text\">Filter entfernen</span>");
    String action = tagWith(empty, "data-testid=\"empty-state-action\"");
    assertThat(action).startsWith("<a ").contains("href=\"/new\"");
    assertThat(empty).contains(">Alle zurücksetzen</a>");
  }

  /** The toolbar search keeps the submitted value and is excluded from the filter chips. */
  @Test
  void toolbarSearchKeepsValueAndIsChipIgnored() {
    String input = tagWith(html, "data-testid=\"toolbar-search\"");
    assertThat(input)
        .contains("type=\"search\"")
        .contains("name=\"q\"")
        .contains("value=\"quanta\"")
        .contains("data-filter-chip-ignore")
        .contains("aria-label=\"Filter\"");
  }

  /**
   * The segmented control is a labelled radio group whose radios keep the form name, check the
   * selected value, map a {@code null} value to the empty "all" option and show counts.
   */
  @Test
  void segmentedRendersRadiosWithSelectionAndCounts() {
    String group = tagWith(html, "role=\"radiogroup\"");
    assertThat(group).contains("class=\"segmented\"").contains("aria-label=\"Filter\"");
    assertThat(html)
        .containsPattern(
            "data-testid=\"segment-scope-all\"> <input type=\"radio\" name=\"scope\" value=\"\""
                + " />");
    assertThat(html)
        .containsPattern(
            "data-testid=\"segment-scope-mine\"> <input type=\"radio\" name=\"scope\""
                + " value=\"MINE\" checked=\"checked\" />");
    assertThat(html).containsPattern("<span class=\"seg-count\">3</span>");
  }

  /** The chip bar starts hidden, names its form and carries the localized remove label. */
  @Test
  void filterChipsRenderHiddenBarBoundToForm() {
    String bar = tagWith(html, "data-filter-chips ");
    assertThat(bar)
        .contains(" hidden")
        .contains("data-filter-form=\"harness-filter-form\"")
        .contains("data-remove-label=\"Filter entfernen\"");
    assertThat(html).containsPattern("data-filter-chips-reset[^>]*>Alle zurücksetzen</button>");
  }
}
