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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.Rule;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the „Mein Lager" tree through the chevron of a material group and of its stack, the
 * level's disclosure button: by keyboard it expands and collapses the level on Enter and Space,
 * mirrors the state in {@code aria-expanded}, loads the stack's entries on the first expand and
 * comes back expanded after a reload; it shows the orange focus ring, and on the touch classes it
 * carries a hit area of the touch floor without wrapping its name (REQ-INV-002, REQ-UI-009).
 */
@Tag("e2e")
class LagerTreeDisclosureE2eTest {

  /** Provisions the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The wait for a server-rendered tree or a lazily loaded stack, in milliseconds. */
  private static final double LOAD_TIMEOUT_MS = 20_000;

  /** The value of {@code --touch-target}, in CSS pixels. */
  private static final double TOUCH_FLOOR = 44;

  /** Measures a chevron: its {@code ::before} overlay, its row, and where its name starts. */
  private static final String MEASURE_CHEVRON =
      """
      button => {
        const overlay = getComputedStyle(button, '::before');
        const row = button.closest('.tree-row');
        const label = row.querySelector('.tree-label');
        const box = button.getBoundingClientRect();
        return JSON.stringify({
          overlayPosition: overlay.position,
          overlayWidth: parseFloat(overlay.width),
          overlayHeight: parseFloat(overlay.height),
          rowHeight: row.getBoundingClientRect().height,
          labelBesideButton: label.getBoundingClientRect().left >= box.right
            && label.getBoundingClientRect().top < box.bottom
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String materialId;
  private static String itemId;

  /** Launches the browser and seeds one shared stock row of an own material at an own place. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "the flow seeds its own stock row");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    materialId = seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Lager Disclosure Mat");
    String locationId = seeder.createLocation(USERNAME, PASSWORD, "E2E Lager Disclosure Hub");
    itemId = seeder.createInventoryItem(USERNAME, PASSWORD, materialId, locationId, 700, 25.0);
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
   * Reaches the group's chevron from its checkbox with Shift+Tab, checks its focus ring, opens the
   * group with Enter and the stack with Space, scans the open tree with axe, reloads to the
   * restored state, and closes both levels again by keyboard.
   */
  @Test
  void theChevronButtonsDiscloseTheTreeByKeyboard() {
    try (BrowserContext context = newContext(1280, 800)) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/inventory?storage=my");
        Locator groupToggle = groupToggle(page);
        Locator stackToggle = stackToggle(page);
        Locator groupItems = page.locator("#lager-group-" + materialId);
        Locator stackEntries = page.locator(stackRow() + " + div.tree-stack-entries");
        Locator leaf = page.locator("div.tree-row--leaf[data-item-id='" + itemId + "']");

        assertThat(groupToggle).isVisible(afterLoad());
        assertThat(groupToggle).hasAttribute("aria-expanded", "false");
        assertThat(groupToggle).hasAttribute("aria-controls", "lager-group-" + materialId);
        assertThat(groupItems).isHidden();

        page.locator(groupRow() + " input.group-select-all").focus();
        page.keyboard().press("Shift+Tab");
        assertThat(groupToggle).isFocused();
        assertThat(
                groupToggle.evaluate(
                    "b => b.matches(':focus-visible') && getComputedStyle(b).outlineStyle"
                        + " === 'solid' && getComputedStyle(b).outlineWidth === '2px'"
                        + " && getComputedStyle(b).outlineColor === getComputedStyle(b).color"))
            .as("the focused chevron draws the 2px orange focus ring")
            .isEqualTo(true);
        page.keyboard().press("Enter");
        assertThat(groupToggle).hasAttribute("aria-expanded", "true");
        assertThat(groupItems).isVisible();

        assertThat(stackToggle).hasAttribute("aria-expanded", "false");
        assertThat(stackToggle).hasAttribute("aria-controls", stackEntries.getAttribute("id"));
        stackToggle.focus();
        page.keyboard().press("Space");
        assertThat(stackToggle).hasAttribute("aria-expanded", "true");
        assertThat(leaf).isVisible(afterLoad());
        assertNoSeriousViolationsInTheTree(page);

        page.reload();
        assertThat(leaf).isVisible(afterLoad());
        assertThat(groupToggle).hasAttribute("aria-expanded", "true");
        assertThat(stackToggle).hasAttribute("aria-expanded", "true");

        stackToggle.focus();
        page.keyboard().press("Enter");
        assertThat(stackToggle).hasAttribute("aria-expanded", "false");
        assertThat(stackEntries).isHidden();
        groupToggle.focus();
        page.keyboard().press("Space");
        assertThat(groupToggle).hasAttribute("aria-expanded", "false");
        assertThat(groupItems).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "lager-tree-disclosure-keyboard");
        throw failure;
      }
    }
  }

  /**
   * On the tablet and the phone class, the group and stack chevrons each carry a positioned overlay
   * of the touch floor in a row at least that tall, and the name stays beside the chevron.
   */
  @Test
  void theChevronButtonsCarryTheTouchFloorOnTheTouchClasses() {
    for (int[] viewport : new int[][] {{810, 1080}, {375, 812}}) {
      try (BrowserContext context = newContext(viewport[0], viewport[1])) {
        Page page = context.newPage();
        String device = viewport[0] + "x" + viewport[1];
        try {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory?storage=my");
          assertThat(groupToggle(page)).isVisible(afterLoad());
          page.locator(groupRow() + " .tree-label").click();
          assertThat(stackToggle(page)).isVisible(afterLoad());
          SoftAssertions.assertSoftly(soft -> {
            assertTouchFloor(soft, device + " group", measure(groupToggle(page)));
            assertTouchFloor(soft, device + " stack", measure(stackToggle(page)));
          });
        } catch (RuntimeException | AssertionError failure) {
          E2eSupport.dump(page, "lager-tree-disclosure-touch-" + device);
          throw failure;
        }
      }
    }
  }

  /**
   * Opens an authenticated context of the given viewport.
   *
   * @param width the viewport width in CSS pixels
   * @param height the viewport height in CSS pixels
   * @return the context; the caller closes it
   */
  private static @NotNull BrowserContext newContext(int width, int height) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState)
            .setViewportSize(width, height));
  }

  /**
   * The selector of the seeded material's group row.
   *
   * @return the CSS selector
   */
  private static @NotNull String groupRow() {
    return "div.tree-row--group[data-material-id='" + materialId + "']";
  }

  /**
   * The selector of the seeded material's stack header.
   *
   * @return the CSS selector
   */
  private static @NotNull String stackRow() {
    return "div.stack-header[data-material-id='" + materialId + "']";
  }

  /**
   * The chevron button of the seeded material's group row.
   *
   * @param page the Lager page
   * @return the locator
   */
  private static @NotNull Locator groupToggle(@NotNull Page page) {
    return page.locator(groupRow() + " button.tree-chevron");
  }

  /**
   * The chevron button of the seeded material's stack header.
   *
   * @param page the Lager page
   * @return the locator
   */
  private static @NotNull Locator stackToggle(@NotNull Page page) {
    return page.locator(stackRow() + " button.tree-chevron");
  }

  /**
   * Measures one chevron through {@link #MEASURE_CHEVRON}.
   *
   * @param toggle the chevron button
   * @return the measurements by name
   */
  private static @NotNull JsonObject measure(@NotNull Locator toggle) {
    return JsonParser.parseString((String) toggle.evaluate(MEASURE_CHEVRON)).getAsJsonObject();
  }

  /**
   * Asserts that a measured chevron reaches the touch floor through a positioned overlay in a row
   * at least as tall, with its name beside it.
   *
   * @param soft the collecting assertions
   * @param what the device class and level, for the messages
   * @param chevron the measurements of {@link #measure}
   */
  private static void assertTouchFloor(
      @NotNull SoftAssertions soft, @NotNull String what, @NotNull JsonObject chevron) {
    soft.assertThat(chevron.get("overlayPosition").getAsString())
        .as("%s overlay position", what)
        .isEqualTo("absolute");
    soft.assertThat(chevron.get("overlayWidth").getAsDouble())
        .as("%s overlay width", what)
        .isGreaterThanOrEqualTo(TOUCH_FLOOR);
    soft.assertThat(chevron.get("overlayHeight").getAsDouble())
        .as("%s overlay height", what)
        .isGreaterThanOrEqualTo(TOUCH_FLOOR);
    soft.assertThat(chevron.get("rowHeight").getAsDouble())
        .as("%s row height", what)
        .isGreaterThanOrEqualTo(TOUCH_FLOOR);
    soft.assertThat(chevron.get("labelBesideButton").getAsBoolean())
        .as("%s name stays beside the chevron", what)
        .isTrue();
  }

  /**
   * The visibility wait for a server-rendered tree or a lazily loaded stack.
   *
   * @return fresh options carrying {@link #LOAD_TIMEOUT_MS}
   */
  private static LocatorAssertions.@NotNull IsVisibleOptions afterLoad() {
    return new LocatorAssertions.IsVisibleOptions().setTimeout(LOAD_TIMEOUT_MS);
  }

  /**
   * Scans the open Lager tree, its loaded entries included, with axe for WCAG A+AA and fails on a
   * critical or serious violation.
   *
   * @param page the page showing the open tree
   */
  private static void assertNoSeriousViolationsInTheTree(@NotNull Page page) {
    List<Rule> gated =
        new AxeBuilder(page)
                .include("#inventoryTable")
                .withTags(AccessibilitySmokeE2eTest.WCAG_TAGS)
                .analyze()
                .getViolations()
                .stream()
                .filter(rule -> AccessibilitySmokeE2eTest.GATED_IMPACTS.contains(rule.getImpact()))
                .toList();
    assertThat(gated)
        .as(
            "critical/serious accessibility violations in the Lager tree:%n%s",
            gated.stream().map(AccessibilitySmokeE2eTest::describe).collect(Collectors.joining()))
        .isEmpty();
  }
}
