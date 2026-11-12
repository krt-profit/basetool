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

package de.greluc.krt.profit.basetool.frontend.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.assertj.core.api.SoftAssertions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks without a stack that the last visible row of the Lager tree draws no bottom rule on the
 * list panel's frame, while every visible row above it keeps its rule (REQ-UI-007, REQ-INV-002).
 */
@Tag("e2e")
class LagerTreeLastRowE2eTest {

  private static final String ORIGIN = "https://lager-tree.test";

  /** The width of {@code --border-hairline}, in CSS pixels. */
  private static final double HAIRLINE = 1.0;

  /**
   * A Lager tree as the templates render it: two material groups, the second with two stacks, the
   * second stack's entries loaded with a leaf and its note, and every container collapsed.
   */
  private static final String FIXTURE =
      """
      <!DOCTYPE html><html lang="de"><head>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <link rel="stylesheet" href="/css/kernel/styles.css">
      <link rel="stylesheet" href="/css/inventory/inventory-lager.css">
      </head><body><main class="lager-page"><section class="list-panel">
      <div class="toolbar lager-toolbar"><span>Filter</span></div>
      <div class="card card--flush lager-tree-card"><div class="overflow-x-auto">
      <div class="table-responsive"><div class="tree-table lager-material-tree">
        <div class="tree-row tree-head"><div class="tree-cell">Bestand</div></div>
        <div class="tree-group">
          <div class="tree-row tree-row--group" id="g1">
            <div class="tree-cell tree-name"><span class="tree-label">Agricium</span></div>
          </div>
          <div class="tree-group-items" id="g1-items" hidden><div>
            <div class="tree-row tree-row--mid stack-header" id="g1-s1">
              <div class="tree-cell tree-name"><span class="tree-label">ARC-L1</span></div>
            </div>
            <div class="tree-stack-entries" hidden><div class="stack-entries-content"></div></div>
          </div></div>
        </div>
        <div class="tree-group">
          <div class="tree-row tree-row--group" id="g2">
            <div class="tree-cell tree-name"><span class="tree-label">Quantanium</span></div>
          </div>
          <div class="tree-group-items" id="g2-items" hidden>
            <div>
              <div class="tree-row tree-row--mid stack-header" id="g2-s1">
                <div class="tree-cell tree-name"><span class="tree-label">ARC-L1</span></div>
              </div>
              <div class="tree-stack-entries" hidden><div class="stack-entries-content"></div></div>
            </div>
            <div>
              <div class="tree-row tree-row--mid stack-header" id="g2-s2">
                <div class="tree-cell tree-name"><span class="tree-label">HUR-L2</span></div>
              </div>
              <div class="tree-stack-entries" id="g2-s2-entries" hidden>
                <div class="stack-entries-content">
                  <div class="tree-row tree-row--leaf" id="g2-s2-leaf">
                    <div class="tree-leaf-main"><span>12,500 SCU</span></div>
                  </div>
                  <div class="tree-note" id="g2-s2-note">
                    <div class="tree-note-box"><span class="tree-note-text">Notiz</span></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div></div></div></div>
      </section></main></body></html>
      """;

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the configured browser. */
  @BeforeAll
  static void openBrowser() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, false);
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void closeBrowser() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Opens the containers of one collapse state the way the Lager script does, through their {@code
   * hidden} attribute, and asserts which visible rows draw a bottom rule.
   *
   * @param state the collapse state
   */
  @ParameterizedTest
  @EnumSource(TreeState.class)
  void theLastVisibleRowDrawsNoBottomRule(@NotNull TreeState state) {
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800))) {
      context.route(ORIGIN + "/**", route -> StaticFixtureServer.serve(route, FIXTURE));
      Page page = context.newPage();
      page.navigate(ORIGIN + "/");
      page.evaluate(
          "([open, note]) => {"
              + " open.forEach(id => document.getElementById(id).toggleAttribute('hidden', false));"
              + " if (!note) document.getElementById('g2-s2-note').remove(); }",
          List.of(state.open, state.note));
      SoftAssertions.assertSoftly(soft -> {
        soft.assertThat(page.locator("#" + state.lastRow).isVisible())
            .as("%s: %s is shown", state, state.lastRow)
            .isTrue();
        soft.assertThat(bottomRule(page, state.lastRow))
            .as("%s: %s ends the tree and draws no rule", state, state.lastRow)
            .isZero();
        for (String id : state.ruledRows) {
          soft.assertThat(page.locator("#" + id).isVisible())
              .as("%s: %s is shown", state, id)
              .isTrue();
          soft.assertThat(bottomRule(page, id))
              .as("%s: %s keeps its rule", state, id)
              .isEqualTo(HAIRLINE);
        }
      });
      E2eSupport.capture(page, "lager-tree-last-row-" + state.name().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * The computed bottom border width of one fixture row.
   *
   * @param page the fixture page
   * @param id the row's id
   * @return the width in CSS pixels
   */
  private static double bottomRule(@NotNull Page page, @NotNull String id) {
    return ((Number)
            page.evaluate(
                "id => parseFloat(getComputedStyle(document.getElementById(id)).borderBottomWidth)",
                id))
        .doubleValue();
  }

  /** A collapse state of the fixture tree and the rows whose bottom rule it decides. */
  @RequiredArgsConstructor
  private enum TreeState {
    /** Every group collapsed: the last group row ends the tree. */
    ALL_COLLAPSED(List.of(), true, "g2", List.of("g1")),
    /** An earlier group open: its stack keeps its rule, the last group row ends the tree. */
    EARLIER_GROUP_OPEN(List.of("g1-items"), true, "g2", List.of("g1", "g1-s1")),
    /** The last group open on a closed stack: that stack header ends the tree. */
    LAST_STACK_CLOSED(List.of("g2-items"), true, "g2-s2", List.of("g1", "g2", "g2-s1")),
    /** The last stack open and its last entry without a note: the leaf ends the tree. */
    LEAF_LAST(List.of("g2-items", "g2-s2-entries"), false, "g2-s2-leaf", List.of("g2-s1", "g2-s2")),
    /** The last stack open and its last entry with a note: the note ends the tree. */
    NOTE_LAST(
        List.of("g2-items", "g2-s2-entries"), true, "g2-s2-note", List.of("g2-s2", "g2-s2-leaf"));

    /** The ids of the containers the state shows. */
    private final @NotNull List<String> open;

    /** Whether the last entry keeps its note. */
    private final boolean note;

    /** The id of the row that ends the tree. */
    private final @NotNull String lastRow;

    /** The ids of visible rows above it that keep their bottom rule. */
    private final @NotNull List<String> ruledRows;
  }
}
