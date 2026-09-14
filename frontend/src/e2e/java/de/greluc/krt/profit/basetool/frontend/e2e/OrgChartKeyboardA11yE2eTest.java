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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Interactive accessibility flows for the org chart ({@code /org-chart}, REQ-ORG-013). The static
 * ARIA scaffolding (tree/group/treeitem roles, {@code aria-level}s, the {@code aria-pressed} edit
 * toggle, the edit-mode hint) is pinned by the template-render unit test {@code
 * OrgChartPageRenderTest}; this suite covers the parts that only exist at runtime in the page's
 * inline JavaScript and so cannot be asserted on rendered HTML:
 *
 * <ol>
 *   <li>the roving tabindex — exactly one treeitem is tabbable at a time — and arrow-key / Home /
 *       End focus movement through the tree;
 *   <li>the editor dialog's focus trap (Tab / Shift+Tab cycle within {@code .modal-content}), its
 *       Esc-to-close, the focus return to the triggering control, and the {@code inert} + {@code
 *       aria-hidden} page chrome while it is open;
 *   <li>the preservation of the chart's horizontal scroll position across the full-page reload that
 *       a successful edit performs (no snap back to the top-left).
 * </ol>
 *
 * <p>Driven as the seeded ADMIN user ({@code test-admin}) so the inline editor affordances render.
 * The chart is descriptive and not org-unit-scoped, so — unlike the Mission / Job-Order flows — the
 * user needs no OrgUnit membership; {@link E2eStackExtension}'s bootstrap opt-in of the IRIDIUM
 * Squadron is enough to render a non-empty, editable chart. Tagged {@code @Tag("e2e")} (not {@code
 * smoke}): the scroll-preservation case creates throwaway Kommando rows, so it must run only
 * against the ephemeral, disposable stack — never a shared staging deployment.
 */
@Tag("e2e")
class OrgChartKeyboardA11yE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** CSS selector for the live tree nodes the roving tabindex moves focus between. */
  private static final String TREE_ITEMS = ".oc-tree [role='treeitem']";

  /** CSS selector for the single currently-tabbable tree node ({@code tabindex="0"}). */
  private static final String TABBABLE_ITEM = ".oc-tree [role='treeitem'][tabindex='0']";

  /**
   * The always-present "Kommandogruppe hinzufügen" add affordance. A Kommando ({@code
   * COMMAND_LEAD}) is the one rank that may be created leaderless, so the scroll test can widen the
   * chart with extra columns without needing a distinct user per node.
   */
  private static final String ADD_COMMAND_BUTTON =
      "[data-trigger='oc-add'][data-position-type='COMMAND_LEAD'][data-needs-name='true']";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated ADMIN session reused across the tests. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Drives the roving tabindex: on load exactly one treeitem is tabbable (the first), End / Home
   * jump focus to the last / first node, and ArrowRight / ArrowLeft descend to a child level and
   * ascend back to the parent — with the "exactly one {@code tabindex="0"}" invariant holding after
   * every move.
   */
  @Test
  void rovingTabindexMovesFocusWithArrowAndHomeEndKeys() {
    try (BrowserContext context = authedContext(1280, 800)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/org-chart");
        // The roving init runs in the end-of-body script; wait until a node is actually tabbable
        // rather than merely present, so the assertions below never race the initialisation.
        page.waitForSelector(TABBABLE_ITEM);

        Locator items = page.locator(TREE_ITEMS);
        int count = items.count();
        assertTrue(count >= 2, "seeded chart should render at least two tree nodes, got " + count);

        // On load: exactly one tabbable node, and it is the first in document order.
        assertThat(page.locator(TABBABLE_ITEM)).hasCount(1);
        assertThat(items.first()).hasAttribute("tabindex", "0");

        // End -> last node, Home -> first node; the single-tabbable invariant survives both.
        items.first().focus();
        page.keyboard().press("End");
        assertThat(items.last()).isFocused();
        assertThat(items.last()).hasAttribute("tabindex", "0");
        assertThat(page.locator(TABBABLE_ITEM)).hasCount(1);

        page.keyboard().press("Home");
        assertThat(items.first()).isFocused();
        assertThat(page.locator(TABBABLE_ITEM)).hasCount(1);

        // The first node is the Bereichsleiter at level 1. ArrowRight descends to its first child
        // (the next, one-level-deeper node); ArrowLeft ascends back to it.
        assertEquals("1", activeAriaLevel(page), "first node is the level-1 Bereichsleiter");
        page.keyboard().press("ArrowRight");
        assertEquals("2", activeAriaLevel(page), "ArrowRight moves to the level-2 first child");
        assertThat(page.locator(TABBABLE_ITEM)).hasCount(1);

        page.keyboard().press("ArrowLeft");
        assertEquals("1", activeAriaLevel(page), "ArrowLeft moves back to the level-1 parent");
        assertThat(page.locator(TABBABLE_ITEM)).hasCount(1);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-chart-roving");
        throw failure;
      }
    }
  }

  /**
   * Opens the editor dialog and verifies it is modal in the keyboard sense: the page chrome behind
   * it is {@code inert} + {@code aria-hidden}, Tab / Shift+Tab wrap within {@code .modal-content}
   * (never escaping to the background), Esc closes it, and focus returns to the control that opened
   * it.
   */
  @Test
  void editorDialogTrapsFocusAndRestoresItOnEscape() {
    try (BrowserContext context = authedContext(1280, 800)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/org-chart");
        enterEditMode(page);

        // The "Stab hinzufügen" button is always present for an admin and opens the dialog with
        // both the staff-type and user pickers, giving several focusables to trap between.
        Locator trigger = page.locator("[data-trigger='oc-add-staff']");
        trigger.click();
        Locator modal = page.locator("#oc-modal");
        assertThat(modal).isVisible();

        // The page chrome is taken out of the tab order and hidden from assistive tech.
        assertTrue(
            isInert(page, "main"), "background <main> must be inert while the dialog is open");
        assertThat(page.locator("main")).hasAttribute("aria-hidden", "true");

        // Tab repeatedly: focus must never leave the dialog content.
        for (int i = 0; i < 8; i++) {
          page.keyboard().press("Tab");
          assertTrue(focusInsideModalContent(page), "Tab #" + (i + 1) + " let focus escape dialog");
        }

        // The trap wraps at both ends: from the first focusable Shift+Tab lands on the last, and
        // from the last focusable Tab lands on the first. The "×" close button is the first
        // focusable in DOM order; the submit button is the last.
        Locator firstFocusable = page.locator("#oc-modal .oc-modal-close");
        Locator lastFocusable = page.locator("#oc-modal [data-trigger='oc-modal-submit']");

        firstFocusable.focus();
        page.keyboard().press("Shift+Tab");
        assertThat(lastFocusable).isFocused();

        lastFocusable.focus();
        page.keyboard().press("Tab");
        assertThat(firstFocusable).isFocused();

        // Esc closes the dialog, restores the chrome and returns focus to the triggering button.
        page.keyboard().press("Escape");
        assertThat(modal).isHidden();
        assertThat(trigger).isFocused();
        assertThat(page.locator("main")).not().hasAttribute("aria-hidden", "true");
        assertTrue(!isInert(page, "main"), "background <main> must no longer be inert once closed");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-chart-modal-trap");
        throw failure;
      }
    }
  }

  /**
   * Verifies that a successful edit keeps the chart's horizontal scroll position across the
   * post-save in-place refresh. On a narrow viewport the chart is first widened with a few
   * leaderless Kommando columns until it scrolls horizontally; the rightmost Kommando is then
   * renamed with the chart scrolled fully to the right. After the refresh the restored {@code
   * scrollLeft} must match that pre-save value, not snap back to zero.
   *
   * <p>The rename is deliberate on two counts. First, the editor re-focuses the triggering control
   * on close — and a {@code focus()} scrolls that control into view, which would itself reset the
   * scroll if the trigger were off-screen; driving the <em>rightmost</em> control with the chart
   * scrolled fully right keeps that control visible, so the focus moves nothing and the saved
   * scroll is the value under test. Second, a rename leaves the chart width unchanged, so the
   * scroll range is identical before and after the refresh and the restored offset must match
   * exactly.
   */
  @Test
  void successfulEditPreservesHorizontalScrollPosition() {
    // A narrow viewport so a handful of side-by-side Kommando columns overflow the chart container
    // and make it horizontally scrollable; tall enough that the editor dialog fits comfortably.
    try (BrowserContext context = authedContext(500, 900)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/org-chart");

        // Always create at least one leaderless Kommando (so a renameable node exists for the
        // oc-rename step below), then keep adding — up to the 4/Staffel cap — until the chart
        // overflows horizontally. The shared ephemeral stack may already make the all-Staffeln
        // chart
        // scrollable (sibling suites seed extra Staffeln/SKs into it), in which case a width-only
        // condition would create none and leave no oc-rename control to drive. Each create
        // refreshes
        // the chart in place; edit mode survives it and is re-entered idempotently inside the
        // helper.
        for (int i = 0; i < 4 && (i == 0 || maxScrollLeft(page) <= 0); i++) {
          createLeaderlessKommando(page, "E2E Breite " + i);
        }
        assertTrue(
            maxScrollLeft(page) > 0,
            "chart should be horizontally scrollable after adding Kommando columns");

        // Rename the rightmost Kommando — a genuine, in-place-refreshing edit that does not change
        // the chart width. Clicking the control scrolls it into view; then scroll fully right so
        // that
        // the
        // control the editor re-focuses on close is already visible (no focus-driven scroll reset).
        enterEditMode(page);
        page.locator("[data-trigger='oc-rename']").last().click();
        assertThat(page.locator("#oc-modal")).isVisible();

        int target = scrollChartToRightEnd(page);
        assertTrue(target > 0, "precondition: chart must be scrolled away from the left edge");

        page.locator("#oc-name").fill("E2E Renamed");
        clickAndAwaitRefresh(page, page.locator("#oc-modal [data-trigger='oc-modal-submit']"));

        // After the in-place refresh swaps a fresh tree into #oc-chart (which resets the
        // container's
        // scroll), refreshChart() restores the captured horizontal offset in its swap .then(); wait
        // for that restore to land before reading, so the assertions below do not race it.
        page.waitForFunction(
            "(t) => { const el = document.getElementById('oc-chart');"
                + " return el && Math.abs(Math.round(el.scrollLeft) - t) <= 3; }",
            target,
            new Page.WaitForFunctionOptions().setTimeout(10_000));
        int restored = scrollLeft(page);
        assertTrue(
            restored > 0,
            "horizontal scroll snapped back to the left edge after a successful edit");
        assertTrue(
            Math.abs(restored - target) <= 3,
            "restored scrollLeft " + restored + " should match the pre-save value " + target);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-chart-scroll-preserve");
        throw failure;
      }
    }
  }

  // ---------------------------------------------------------------------- helpers --

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate), the reused ADMIN storage state, and a fixed viewport.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext(int width, int height) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState)
            .setViewportSize(width, height));
  }

  /**
   * Turns the inline editor on (idempotently), so the dashed add affordances and the per-node
   * action controls become visible. Edit mode survives an in-place chart refresh (the {@code
   * editing} class lives on the stable {@code #oc-chart} container), so the guard is really for the
   * initial page load, where the toggle starts off; calling it after a refresh is a harmless no-op.
   *
   * @param page the page showing the org chart
   */
  private static void enterEditMode(Page page) {
    Locator toggle = page.locator("[data-trigger='oc-toggle-edit']");
    toggle.waitFor();
    if (!"true".equals(toggle.getAttribute("aria-pressed"))) {
      toggle.click();
    }
    assertThat(page.locator("#oc-chart.editing")).hasCount(1);
  }

  /**
   * Creates a single leaderless Kommando(gruppe) on the first Staffel through the inline editor and
   * blocks until the post-save in-place chart refresh has settled. Used only to widen the chart for
   * the scroll-preservation assertion; the holder is intentionally left empty (a {@code
   * COMMAND_LEAD} is the one rank that may be created vacant).
   *
   * @param page the page showing the org chart
   * @param name the Kommando name to enter
   */
  private static void createLeaderlessKommando(Page page, String name) {
    enterEditMode(page);
    page.locator(ADD_COMMAND_BUTTON).first().click();
    assertThat(page.locator("#oc-modal")).isVisible();
    page.locator("#oc-name").fill(name);
    clickAndAwaitRefresh(page, page.locator("#oc-modal [data-trigger='oc-modal-submit']"));
  }

  /**
   * Clicks a control that triggers the editor's "save then in-place refresh" path and blocks until
   * the refresh has committed. A successful edit no longer reloads the page: it re-renders the
   * whole tree via {@code krtFetch.swap(?fragment=chartBody)} into the stable {@code #oc-chart}
   * container (epic #571 / REQ-FE-005), which dispatches a {@code krt:swapped} event on {@code
   * document} once the new subtree is in the DOM. A one-shot listener scoped to {@code #oc-chart}
   * flips a sentinel on exactly that commit, so waiting for the sentinel proves the fresh tree is
   * in place before the caller inspects anything.
   *
   * @param page the page that hosts the chart
   * @param submit the submit control to click
   */
  private static void clickAndAwaitRefresh(Page page, Locator submit) {
    page.evaluate(
        "() => {"
            + "  window.__ocSwapped = false;"
            + "  const chart = document.getElementById('oc-chart');"
            + "  document.addEventListener('krt:swapped', function onSwap(e) {"
            + "    if (e && e.detail && e.detail.container === chart) {"
            + "      window.__ocSwapped = true;"
            + "      document.removeEventListener('krt:swapped', onSwap);"
            + "    }"
            + "  });"
            + "}");
    submit.click();
    // 30 s, not 15 s: the in-place refresh re-renders the entire org chart, which on the shared
    // ephemeral stack keeps growing as sibling suites seed Staffeln/SKs into it. Under CI load that
    // full re-render has overrun a 15 s budget (the create step times out before the chart
    // returns).
    page.waitForFunction(
        "() => window.__ocSwapped === true",
        null,
        new Page.WaitForFunctionOptions().setTimeout(30_000));
  }

  /**
   * Reads the {@code aria-level} of the element that currently holds focus.
   *
   * @param page the page to inspect
   * @return the focused element's {@code aria-level}, or {@code null} if it has none
   */
  private static String activeAriaLevel(Page page) {
    return (String)
        page.evaluate(
            "() => { const a = document.activeElement;"
                + " return a ? a.getAttribute('aria-level') : null; }");
  }

  /**
   * Reports whether the given element carries the boolean {@code inert} attribute.
   *
   * @param page the page to inspect
   * @param selector a CSS selector for the element
   * @return {@code true} if the element exists and is {@code inert}
   */
  private static boolean isInert(Page page, String selector) {
    return Boolean.TRUE.equals(
        page.evaluate(
            "(sel) => { const el = document.querySelector(sel);"
                + " return !!el && el.hasAttribute('inert'); }",
            selector));
  }

  /**
   * Reports whether keyboard focus currently rests on an element inside the dialog's {@code
   * .modal-content} — the assertion that proves the Tab focus-trap is holding.
   *
   * @param page the page to inspect
   * @return {@code true} if {@code document.activeElement} is within {@code #oc-modal
   *     .modal-content}
   */
  private static boolean focusInsideModalContent(Page page) {
    return Boolean.TRUE.equals(
        page.evaluate(
            "() => { const mc = document.querySelector('#oc-modal .modal-content');"
                + " return !!mc && mc.contains(document.activeElement); }"));
  }

  /**
   * The chart container's maximum horizontal scroll offset ({@code scrollWidth - clientWidth}); a
   * positive value means the chart overflows its container and can be scrolled horizontally.
   *
   * @param page the page showing the org chart
   * @return the maximum {@code scrollLeft}, or {@code -1} if the chart is absent
   */
  private static int maxScrollLeft(Page page) {
    return ((Number)
            page.evaluate(
                "() => { const el = document.getElementById('oc-chart');"
                    + " return el ? Math.round(el.scrollWidth - el.clientWidth) : -1; }"))
        .intValue();
  }

  /**
   * The chart container's current horizontal scroll offset.
   *
   * @param page the page showing the org chart
   * @return the current {@code scrollLeft}, or {@code -1} if the chart is absent
   */
  private static int scrollLeft(Page page) {
    return ((Number)
            page.evaluate(
                "() => { const el = document.getElementById('oc-chart');"
                    + " return el ? Math.round(el.scrollLeft) : -1; }"))
        .intValue();
  }

  /**
   * Scrolls the chart fully to its right end and returns the resulting (browser-clamped) offset.
   * The right end is chosen so the rightmost editor control stays visible — the save path
   * re-focuses that control, and a visible target means {@code focus()} scrolls nothing, so the
   * captured offset is exactly this value.
   *
   * @param page the page showing the org chart
   * @return the chart's {@code scrollLeft} after scrolling to the right end
   */
  private static int scrollChartToRightEnd(Page page) {
    return ((Number)
            page.evaluate(
                "() => { const el = document.getElementById('oc-chart');"
                    + " el.scrollLeft = el.scrollWidth - el.clientWidth;"
                    + " return Math.round(el.scrollLeft); }"))
        .intValue();
  }
}
