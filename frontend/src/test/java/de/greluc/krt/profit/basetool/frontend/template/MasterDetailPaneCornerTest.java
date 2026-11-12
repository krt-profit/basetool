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

import static de.greluc.krt.profit.basetool.frontend.template.CssRules.declares;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.template.CssRules.Rule;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Build-time check of the master-detail pane composition {@code .master-detail--panes}
 * (REQ-UI-007): in the kernel, a master list and a detail pane that share one framed box are
 * frameless panes without a radius or a fill of their own, and the list pane fills its grid row and
 * takes the box's inner radius only in the corners it fills, clipping its rows there; the screens
 * that compose it use the modifier and keep no pane radius, fill or divider of their own.
 */
class MasterDetailPaneCornerTest {

  /** The kernel stylesheet that owns the modifier. */
  private static final String KERNEL = "static/css/kernel/styles.css";

  /** The Materialbörse page stylesheet. */
  private static final String MATERIALBOERSE_SHEET =
      "static/css/pages/materialexchange/materialboerse.css";

  /** The blueprint collection's stylesheet. */
  private static final String BLUEPRINT_SHEET =
      "static/css/personalinventory/personal-inventory.css";

  /** The list pane of the composition, which is also the scroll list. */
  private static final String LIST_PANE = ".master-detail--panes > .master-list";

  /** The detail pane of the composition. */
  private static final String DETAIL_PANE = ".master-detail--panes > .detail-pane";

  /** The first visible row of the list pane, at any depth below it. */
  private static final String FIRST_ROW = LIST_PANE + " .master-row:nth-child(1 of :not([hidden]))";

  /** The last visible row of the list pane, at any depth below it. */
  private static final String LAST_ROW =
      LIST_PANE + " .master-row:nth-last-child(1 of :not([hidden]))";

  /** The list pane's corners beside the detail pane: the box's left corners. */
  private static final String SIDE_BY_SIDE =
      "var(--flush-corner-top) 0 0 var(--flush-corner-bottom)";

  /** The list pane's corners when it stands alone in the stacked layout: all four box corners. */
  private static final String STACKED =
      "var(--flush-corner-top) var(--flush-corner-top) var(--flush-corner-bottom)"
          + " var(--flush-corner-bottom)";

  /** The inner radius of a box with a 1 px frame. */
  private static final String INNER_RADIUS = "calc(var(--radius-box) - 1px)";

  /** The radius properties a pane or a row of the composition leaves to the kernel. */
  private static final List<String> RADIUS =
      List.of(
          "border-radius",
          "border-top-left-radius",
          "border-top-right-radius",
          "border-bottom-right-radius",
          "border-bottom-left-radius");

  /** The radius, fill and divider properties a pane of the composition leaves to the kernel. */
  private static final List<String> PANE_PROPERTIES =
      Stream.concat(
              RADIUS.stream(),
              Stream.of("background", "background-color", "border", "border-right"))
          .toList();

  /** A parenthesised argument, such as the selector list of {@code :nth-child(1 of …)}. */
  private static final Pattern ARGUMENT = Pattern.compile("\\((?:[^()]|\\([^()]*\\))*\\)");

  /**
   * One page stylesheet that styles a screen built on the composition.
   *
   * @param stylesheet the stylesheet, relative to the resources root
   * @param paneClasses the classes, with their dot, of the composition, its list pane and its
   *     detail pane on that screen
   * @param rowClasses the classes, with their dot, of its rows
   */
  record PageSheet(
      @NotNull String stylesheet,
      @NotNull List<String> paneClasses,
      @NotNull List<String> rowClasses) {}

  /**
   * Lists the page stylesheets of the Materialbörse and the blueprint collection.
   *
   * @return the page stylesheets
   */
  static @NotNull Stream<PageSheet> pageSheets() {
    return Stream.of(
        new PageSheet(
            MATERIALBOERSE_SHEET,
            List.of(".mb-md", ".mb-mlist", ".mb-detailpane"),
            List.of(".mb-mrow")),
        new PageSheet(
            BLUEPRINT_SHEET,
            List.of(".krt-bp-md", ".master-list", ".detail-pane"),
            List.of(".master-row")));
  }

  /**
   * Asserts that the detail pane takes neither the box radius nor a frame or a fill, so the box
   * paints its corners.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theDetailPaneIsAFramelessPane() throws IOException, URISyntaxException {
    assertThat(CssRules.declarations(CssRules.read(KERNEL), DETAIL_PANE))
        .as("REQ-UI-007: `%s` takes no radius, no frame and no fill", DETAIL_PANE)
        .anyMatch(body ->
            declares(body, "border-radius", "0")
                && declares(body, "border", "0")
                && declares(body, "background", "transparent"));
  }

  /**
   * Asserts that the list pane is a scroll list without a fill that fills its grid row, keeps a
   * straight divider, and takes the box's inner radius in the box's left corners beside the detail
   * pane and in all four when it stands alone.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theListPaneFillsItsRowAndTakesOnlyTheCornersItFills()
      throws IOException, URISyntaxException {
    List<String> list = CssRules.declarations(CssRules.read(KERNEL), LIST_PANE);
    assertThat(list)
        .as("REQ-UI-007: `%s` fills its row, scrolls and clips, and has no fill", LIST_PANE)
        .anyMatch(body ->
            declares(body, "min-height", "100%")
                && declares(body, "overflow-y", "auto")
                && declares(body, "background", "transparent")
                && declares(body, "border-right", "var(--border-hairline)")
                && declares(body, "border-radius", SIDE_BY_SIDE));
    assertThat(list)
        .as("REQ-UI-007: `%s` takes all four box corners when it stands alone", LIST_PANE)
        .anyMatch(body ->
            declares(body, "border-right", "0") && declares(body, "border-radius", STACKED));
  }

  /**
   * Asserts that the rows of the list pane take no radius of their own, so the kernel's first- and
   * last-row rounding of a stand-alone master list does not round them inside the box, and that the
   * last visible row drops its rule, wherever the rows sit below the list pane.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theRowsTakeNoRadiusAndTheLastVisibleRowDropsItsRule()
      throws IOException, URISyntaxException {
    List<Rule> rules = CssRules.read(KERNEL);
    assertThat(CssRules.declarations(rules, FIRST_ROW))
        .as("REQ-UI-007: a row in a list pane is clipped by the pane, never rounded itself")
        .anyMatch(body -> declares(body, "border-radius", "0"));
    List<String> last = CssRules.declarations(rules, LAST_ROW);
    assertThat(last)
        .as("REQ-UI-007: the last visible row in a list pane is not rounded itself")
        .anyMatch(body -> declares(body, "border-radius", "0"));
    assertThat(last)
        .as("REQ-UI-007: the last visible row in a list pane drops its own bottom rule")
        .anyMatch(body -> declares(body, "border-bottom", "0"));
  }

  /**
   * Asserts that a screen that shares one framed box between a master list and a detail pane builds
   * it on the modifier.
   *
   * @param template the template, relative to the resources root
   * @throws IOException if the template cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "templates/materialexchange/materialboerse.html",
        "templates/materialexchange/fragments/materialgesuch-board.html",
        "templates/blueprint/personal-inventory-blueprints.html"
      })
  void theScreenComposesThePanesModifier(@NotNull String template)
      throws IOException, URISyntaxException {
    assertThat(
            Files.readString(CssSources.mainResources().resolve(template), StandardCharsets.UTF_8))
        .as("REQ-UI-007: %s builds its panes on .master-detail--panes", template)
        .contains("class=\"master-detail master-detail--panes ");
  }

  /**
   * Asserts that a page stylesheet leaves the radius, fill and divider of its panes, and the radius
   * of its rows, to the kernel's modifier: no rule whose subject is one of them sets one.
   *
   * @param sheet the page stylesheet
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @ParameterizedTest
  @MethodSource("pageSheets")
  void thePageStylesheetKeepsNoPaneRulesOfItsOwn(@NotNull PageSheet sheet)
      throws IOException, URISyntaxException {
    List<String> ownPaneRules =
        CssRules.read(sheet.stylesheet()).stream()
            .flatMap(rule ->
                rule.entries().stream()
                    .flatMap(selector ->
                        leftToTheKernel(selector, sheet).stream()
                            .filter(property -> CssRules.sets(rule.body(), property))
                            .map(property -> selector + " { " + property + " }")))
            .toList();
    assertThat(ownPaneRules)
        .as(
            "REQ-UI-007: %s leaves pane radius, fill and divider to .master-detail--panes",
            sheet.stylesheet())
        .isEmpty();
  }

  /**
   * Asserts that the blueprint collection's flush card, which closes its list panel from inside the
   * live-sync wrapper, hands the panel's bottom corner to its content.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theBlueprintCardHandsOnThePanelsBottomCorner() throws IOException, URISyntaxException {
    assertThat(CssRules.declarations(CssRules.read(BLUEPRINT_SHEET), ".krt-bp-md"))
        .as("REQ-UI-007: `.krt-bp-md` sets --flush-corner-bottom to the panel's inner radius")
        .anyMatch(body -> declares(body, "--flush-corner-bottom", INNER_RADIUS));
  }

  /**
   * Lists the properties a page rule leaves to the kernel, by the element its selector styles.
   *
   * @param selector one selector of the rule, with single spaces
   * @param sheet the page stylesheet the rule belongs to
   * @return the radius properties for a row, the pane properties for a pane, else none
   */
  private static @NotNull @Unmodifiable List<String> leftToTheKernel(
      @NotNull String selector, @NotNull PageSheet sheet) {
    String subject = subject(selector);
    if (namesAny(subject, sheet.rowClasses())) {
      return RADIUS;
    }
    if (namesAny(subject, sheet.paneClasses())) {
      return PANE_PROPERTIES;
    }
    return List.of();
  }

  /**
   * Cuts a selector down to its subject, the compound selector after the last combinator.
   *
   * @param selector the selector, with single spaces
   * @return the last compound selector, with each parenthesised argument emptied
   */
  private static @NotNull String subject(@NotNull String selector) {
    String bare = ARGUMENT.matcher(selector).replaceAll("()");
    return bare.substring(bare.lastIndexOf(' ') + 1);
  }

  /**
   * Tells whether a compound selector carries one of the classes.
   *
   * @param compound the compound selector
   * @param classes the classes, with their dot
   * @return whether one of them appears as a whole class name
   */
  private static boolean namesAny(@NotNull String compound, @NotNull List<String> classes) {
    return classes.stream()
        .anyMatch(
            cls -> Pattern.compile(Pattern.quote(cls) + "(?![-\\w])").matcher(compound).find());
  }
}
