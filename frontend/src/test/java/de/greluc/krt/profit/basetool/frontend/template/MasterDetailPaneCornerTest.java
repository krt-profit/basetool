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
import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Build-time check of the master-detail pane composition (REQ-UI-007): a master list and a detail
 * pane that share one framed box are frameless panes without a box radius or a fill of their own,
 * and the list pane fills its grid row and takes the box's inner radius only in the corners it
 * fills, clipping its rows there.
 */
class MasterDetailPaneCornerTest {

  /** The list pane's corners beside the detail pane: the box's left corners. */
  private static final String SIDE_BY_SIDE =
      "var(--flush-corner-top) 0 0 var(--flush-corner-bottom)";

  /** The list pane's corners when it stands alone in the stacked layout: all four box corners. */
  private static final String STACKED =
      "var(--flush-corner-top) var(--flush-corner-top) var(--flush-corner-bottom)"
          + " var(--flush-corner-bottom)";

  /** The inner radius of a box with a 1 px frame. */
  private static final String INNER_RADIUS = "calc(var(--radius-box) - 1px)";

  /**
   * One page that composes a master list and a detail pane inside one framed box.
   *
   * @param stylesheet the page stylesheet, relative to the resources root
   * @param listPane the selector of the list pane, which is also the scroll list
   * @param detailPane the selector of the detail pane
   */
  record Composition(
      @NotNull String stylesheet, @NotNull String listPane, @NotNull String detailPane) {}

  /**
   * Lists the pane compositions: the Materialbörse and the blueprint collection.
   *
   * @return the compositions
   */
  static @NotNull Stream<Composition> compositions() {
    return Stream.of(
        new Composition(
            "static/css/pages/materialexchange/materialboerse.css", ".mb-mlist", ".mb-detailpane"),
        new Composition(
            "static/css/personalinventory/personal-inventory.css",
            ".krt-bp-md .master-list",
            ".krt-bp-md .detail-pane"));
  }

  /**
   * Asserts that the detail pane takes neither the box radius nor a fill, so the box paints its
   * corners.
   *
   * @param composition the page composition
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @ParameterizedTest
  @MethodSource("compositions")
  void theDetailPaneIsAFramelessPane(@NotNull Composition composition)
      throws IOException, URISyntaxException {
    List<String> detail =
        CssRules.declarations(CssRules.read(composition.stylesheet()), composition.detailPane());
    assertThat(detail)
        .as("REQ-UI-007: `%s` takes no radius and no fill", composition.detailPane())
        .anyMatch(body ->
            declares(body, "border-radius", "0") && declares(body, "background", "transparent"));
  }

  /**
   * Asserts that the list pane is a scroll list without a fill that fills its grid row, and that it
   * takes the box's inner radius in the box's left corners beside the detail pane and in all four
   * when it stands alone.
   *
   * @param composition the page composition
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @ParameterizedTest
  @MethodSource("compositions")
  void theListPaneFillsItsRowAndTakesOnlyTheCornersItFills(@NotNull Composition composition)
      throws IOException, URISyntaxException {
    List<String> list =
        CssRules.declarations(CssRules.read(composition.stylesheet()), composition.listPane());
    assertThat(list)
        .as(
            "REQ-UI-007: `%s` fills its row, scrolls and clips, and has no fill",
            composition.listPane())
        .anyMatch(body ->
            declares(body, "min-height", "100%")
                && declares(body, "overflow-y", "auto")
                && declares(body, "background", "transparent")
                && declares(body, "border-radius", SIDE_BY_SIDE));
    assertThat(list)
        .as(
            "REQ-UI-007: `%s` takes all four box corners when it stands alone",
            composition.listPane())
        .anyMatch(body -> declares(body, "border-radius", STACKED));
  }

  /**
   * Asserts that the Materialbörse rows take no radius of their own, so the kernel's first- and
   * last-row rounding of a master list does not round them inside the box.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theMaterialboerseRowsTakeNoRadius() throws IOException, URISyntaxException {
    assertThat(
            CssRules.declarations(
                CssRules.read("static/css/pages/materialexchange/materialboerse.css"), ".mb-mrow"))
        .as("REQ-UI-007: a row in a list pane is clipped by the pane, never rounded itself")
        .anyMatch(body -> declares(body, "border-radius", "0"));
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
    List<Rule> rules = CssRules.read("static/css/personalinventory/personal-inventory.css");
    assertThat(CssRules.declarations(rules, ".krt-bp-md"))
        .as("REQ-UI-007: `.krt-bp-md` sets --flush-corner-bottom to the panel's inner radius")
        .anyMatch(body -> declares(body, "--flush-corner-bottom", INNER_RADIUS));
    assertThat(
            CssRules.declarations(
                rules, ".krt-bp-master-rows > .master-row:nth-last-child(1 of :not([hidden]))"))
        .as("REQ-UI-007: the last visible blueprint row drops its own bottom rule")
        .anyMatch(body -> declares(body, "border-bottom", "0"));
  }
}
