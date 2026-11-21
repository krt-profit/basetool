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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ReducedMotion;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.SoftAssertions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks without a stack that every point of a list-table row outside its controls opens the row
 * link, that the controls and an open overflow menu catch their own clicks, and that the link's cue
 * lights on row hover and keyboard focus (REQ-UI-027 rule 6).
 */
@Tag("e2e")
class RowLinkHitAreaE2eTest {

  private static final String ORIGIN = "https://row-link.test";

  /** The suffix a probe result carries when the point opens its own row's link. */
  private static final String OPENS_ROW = " -> row link";

  /** The suffix a control probe result carries when the control catches the click itself. */
  private static final String CATCHES_ITSELF = " -> itself";

  /** The computed colour of a fully transparent text decoration. */
  private static final String TRANSPARENT = "rgba(0, 0, 0, 0)";

  /**
   * A list table as the list pages render it: a row with a lone chevron, one with a button, one
   * with an overflow menu, an inactive special-command row its page dims, and a last plain row.
   */
  private static final String FIXTURE =
      """
      <!DOCTYPE html><html lang="de"><head>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <link rel="stylesheet" href="/css/kernel/styles.css">
      <link rel="stylesheet" href="/css/kernel/overflow.css">
      <link rel="stylesheet" href="/css/pages/orgunit/admin-special-commands.css">
      <script type="module" src="/js/kernel/krt-overflow-menu.js"></script>
      </head><body><main>
      <div class="card card--flush">
      <table class="data-table data-table--stack">
        <thead><tr><th>Mission</th><th class="cell-status">Status</th><th>Start</th>
          <th class="num">Teilnehmer</th><th class="cell-actions">Aktionen</th></tr></thead>
        <tbody>
          <tr id="row-plain">%1$s
            <td class="cell-actions">%2$s</td></tr>
          <tr id="row-button">%1$s
            <td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs"
              data-probe-control="button">Anmelden</button> %2$s</td></tr>
          <tr id="row-menu">%1$s
            <td class="cell-actions"><div class="overflow-menu" data-overflow-menu>
              <button type="button" class="btn btn-ghost btn-icon overflow-menu__toggle"
                id="menu-toggle" aria-haspopup="menu" aria-expanded="false"
                aria-controls="menu-panel" aria-label="Weitere Aktionen"
                data-probe-control="menu toggle">&hellip;</button>
              <div class="overflow-menu__panel" role="menu" hidden id="menu-panel"
                aria-labelledby="menu-toggle">
                <button type="button" class="overflow-menu__item" role="menuitem">Umbenennen</button>
                <button type="button" class="overflow-menu__item" role="menuitem">Ziel</button>
                <div class="overflow-menu__sep" role="separator"></div>
                <button type="button" class="overflow-menu__item" role="menuitem">Schlie&szlig;en</button>
              </div></div> %2$s</td></tr>
          <tr id="row-inactive" class="sc-row--inactive">%1$s
            <td class="cell-actions">%2$s</td></tr>
          <tr id="row-last">%1$s
            <td class="cell-actions">%2$s</td></tr>
        </tbody>
      </table>
      </div></main></body></html>
      """
          .formatted(
              """
              <td><a class="row-link" href="/rows"><span class="cell-title">Daymar-Bergung</span>
                <span class="cell-sub">Treffpunkt: Port Tressler</span></a></td>
              <td class="cell-status"><span class="status-pill status-planned">Geplant</span></td>
              <td data-label="Start">27.10., 19:30</td>
              <td class="num" data-label="Teilnehmer">7</td>\
              """,
              """
              <svg class="krt-icon row-chevron" viewBox="0 0 16 16" aria-hidden="true">\
              <path d="M6 3l5 5-5 5z"></path></svg>\
              """);

  /** Names the element at a point as {@code tag.class}, or {@code nothing}. */
  private static final String DESCRIBE =
      "const describe = el => el ? el.tagName.toLowerCase()"
          + " + (el.classList.length ? '.' + [...el.classList].join('.') : '') : 'nothing';";

  /**
   * Probes each row's four inner corners, the centre of every cell outside {@code .cell-actions}
   * and the chevron, and reports for each point whether it opens that row's link.
   */
  private static final String PROBE_ROWS =
      "() => { "
          + DESCRIBE
          + """
            return [...document.querySelectorAll('tbody > tr')].flatMap(row => {
              const link = row.querySelector('a.row-link');
              const box = row.getBoundingClientRect();
              const centre = (where, el) => {
                const r = el.getBoundingClientRect();
                return [where, r.left + r.width / 2, r.top + r.height / 2];
              };
              const points = [
                ['top-left', box.left + 4, box.top + 4],
                ['top-right', box.right - 4, box.top + 4],
                ['bottom-left', box.left + 4, box.bottom - 4],
                ['bottom-right', box.right - 4, box.bottom - 4],
                ...[...row.querySelectorAll(':scope > td:not(.cell-actions)')]
                    .map((cell, index) => centre('cell ' + (index + 1), cell)),
                centre('chevron', row.querySelector('.row-chevron'))
              ];
              return points.map(([where, x, y]) => {
                const hit = document.elementFromPoint(x, y);
                const opens = hit !== null && hit.closest('a.row-link') === link;
                return row.id + ' ' + where + (opens ? '%s' : ' -> ' + describe(hit));
              });
            });
          }
          """
              .formatted(OPENS_ROW);

  /** Probes the centre of every element marked as a control and reports whether it is hit. */
  private static final String PROBE_CONTROLS =
      "selector => { "
          + DESCRIBE
          + """
            return [...document.querySelectorAll(selector)].map(control => {
              const r = control.getBoundingClientRect();
              const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
              const label = control.dataset.probeControl || control.textContent.trim();
              return label + (hit !== null && control.contains(hit) ? '%s' : ' -> ' + describe(hit));
            });
          }
          """
              .formatted(CATCHES_ITSELF);

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
   * Asserts that every probed point outside a control opens its own row's link, and that the button
   * and the menu toggle catch their own clicks.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   */
  @ParameterizedTest
  @CsvSource({"375,812", "1280,800"})
  void everyPointOutsideAControlOpensTheRowLink(int width, int height) {
    try (BrowserContext context = fixtureContext(width, height)) {
      Page page = openFixture(context);
      List<String> rows = results(page.evaluate(PROBE_ROWS));
      List<String> controls = results(page.evaluate(PROBE_CONTROLS, "[data-probe-control]"));
      SoftAssertions.assertSoftly(soft -> {
        soft.assertThat(rows).as("row probes at %d px", width).hasSize(5 * 9);
        soft.assertThat(rows)
            .as("REQ-UI-027 rule 6: points that do not open their row at %d px", width)
            .filteredOn(result -> !result.endsWith(OPENS_ROW))
            .isEmpty();
        soft.assertThat(controls).as("control probes at %d px", width).hasSize(2);
        soft.assertThat(controls)
            .as("REQ-UI-027 rule 6: controls covered by the row link at %d px", width)
            .filteredOn(result -> !result.endsWith(CATCHES_ITSELF))
            .isEmpty();
      });
      E2eSupport.capture(page, "row-link-hit-area-" + width);
    }
  }

  /**
   * Opens the overflow menu through its toggle and asserts that every item catches its own click
   * where the panel lies over a neighbouring row.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   */
  @ParameterizedTest
  @CsvSource({"375,812", "1280,800"})
  void anOpenOverflowMenuStaysAboveTheNeighbouringRows(int width, int height) {
    try (BrowserContext context = fixtureContext(width, height)) {
      Page page = openFixture(context);
      page.locator("#menu-toggle").click();
      Locator panel = page.locator("#menu-panel");
      assertThat(panel).isVisible();
      Object overlaps =
          panel.evaluate(
              """
              panel => {
                const p = panel.getBoundingClientRect();
                return [...document.querySelectorAll('tbody > tr:not(#row-menu)')].some(row => {
                  const r = row.getBoundingClientRect();
                  return r.top < p.bottom && r.bottom > p.top;
                });
              }
              """);
      List<String> items =
          results(page.evaluate(PROBE_CONTROLS, "#menu-panel .overflow-menu__item"));
      SoftAssertions.assertSoftly(soft -> {
        soft.assertThat(overlaps).as("the open panel lies over another row").isEqualTo(true);
        soft.assertThat(items).as("menu item probes at %d px", width).hasSize(3);
        soft.assertThat(items)
            .as("REQ-UI-027 rule 6: menu items covered by another row at %d px", width)
            .filteredOn(result -> !result.endsWith(CATCHES_ITSELF))
            .isEmpty();
      });
      E2eSupport.capture(page, "row-link-open-menu-" + width);
    }
  }

  /**
   * Asserts the cue: the chevron is Grau 1 and the title undecorated at rest and while a control is
   * hovered, and both turn house orange on row hover and on keyboard focus of the row link.
   */
  @Test
  void theCueLightsOnRowHoverAndKeyboardFocus() {
    try (BrowserContext context = fixtureContext(1280, 800)) {
      Page page = openFixture(context);
      String primary = tokenColor(page, "--color-primary");
      String gray = tokenColor(page, "--color-gray-1");
      List<String> rest = cue(page, "row-button");
      moveTo(page, "#row-button td[data-label='Start']");
      List<String> rowHover = cue(page, "row-button");
      moveTo(page, "#row-button [data-probe-control]");
      List<String> controlHover = cue(page, "row-button");
      page.mouse().move(0, 0);
      page.keyboard().press("Tab");
      List<String> focus = cue(page, "row-plain");
      SoftAssertions.assertSoftly(soft -> {
        soft.assertThat(rest).as("at rest").containsExactly(gray, TRANSPARENT);
        soft.assertThat(rowHover).as("row hover").containsExactly(primary, primary);
        soft.assertThat(controlHover).as("control hover").containsExactly(gray, TRANSPARENT);
        soft.assertThat(focus).as("keyboard focus").containsExactly(primary, primary);
      });
    }
  }

  /**
   * A context at the given viewport, with reduced motion so the cue's fades end at once, that
   * answers the fixture origin from the checkout's assets.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   * @return the context; the caller closes it
   */
  private static @NotNull BrowserContext fixtureContext(int width, int height) {
    BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setReducedMotion(ReducedMotion.REDUCE));
    context.route(ORIGIN + "/**", route -> StaticFixtureServer.serve(route, FIXTURE));
    return context;
  }

  /**
   * Opens the fixture and waits for its fonts.
   *
   * @param context the fixture context
   * @return the loaded page
   */
  private static @NotNull Page openFixture(@NotNull BrowserContext context) {
    Page page = context.newPage();
    page.navigate(ORIGIN + "/");
    page.evaluate("() => document.fonts.ready.then(() => true)");
    return page;
  }

  /**
   * The probe results of an evaluation that returns an array of strings.
   *
   * @param evaluated the evaluation result
   * @return one string per probe
   */
  private static @NotNull List<String> results(@NotNull Object evaluated) {
    return ((List<?>) evaluated).stream().map(String::valueOf).toList();
  }

  /**
   * Moves the mouse to the centre of an element without Playwright's hit check, which the row
   * link's overlay would fail on purpose.
   *
   * @param page the fixture page
   * @param selector the element to point at
   */
  private static void moveTo(@NotNull Page page, @NotNull String selector) {
    BoundingBox box =
        Objects.requireNonNull(page.locator(selector).boundingBox(), selector + " is laid out");
    page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
  }

  /**
   * The computed chevron colour and title decoration colour of one row.
   *
   * @param page the fixture page
   * @param rowId the row's id
   * @return the chevron's {@code color} and the title's {@code text-decoration-color}
   */
  private static @NotNull List<String> cue(@NotNull Page page, @NotNull String rowId) {
    return results(
        page.evaluate(
            """
            id => [
              getComputedStyle(document.querySelector('#' + id + ' .row-chevron')).color,
              getComputedStyle(document.querySelector('#' + id + ' .cell-title')).textDecorationColor
            ]
            """,
            rowId));
  }

  /**
   * The computed value of a colour token.
   *
   * @param page the fixture page
   * @param token the custom property name
   * @return the token as a computed {@code rgb(…)} colour
   */
  private static @NotNull String tokenColor(@NotNull Page page, @NotNull String token) {
    return String.valueOf(
        page.evaluate(
            """
            token => {
              const probe = document.createElement('span');
              probe.style.color = 'var(' + token + ')';
              document.body.append(probe);
              const colour = getComputedStyle(probe).color;
              probe.remove();
              return colour;
            }
            """,
            token));
  }
}
