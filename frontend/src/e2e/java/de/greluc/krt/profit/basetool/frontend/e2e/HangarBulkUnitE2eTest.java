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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Selects two ships in „Meine Schiffe" and moves them to one of the owner's units and back to
 * „Keine Einheit" from the selection bar, in place (REQ-HANGAR-010, REQ-FE-001).
 */
@Tag("e2e")
class HangarBulkUnitE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    }
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
   * Two freshly added ships are selected, moved to the first unit of the dialog and cleared again;
   * the list shows the unit in place, the selection bar hides after each action, and the page is
   * never reloaded.
   */
  @Test
  void movesASelectionToAUnitAndBackInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String first = "Bulk A " + suffix;
        String second = "Bulk B " + suffix;
        addShip(page, first);
        addShip(page, second);

        page.evaluate("window.__krtNoReload = true;");
        Locator bar = page.getByTestId("hangar-selection-bar");
        assertThat(bar).isHidden();
        row(page, first).getByTestId("hangar-ship-check").check();
        row(page, second).getByTestId("hangar-ship-check").check();
        assertThat(bar).isVisible();
        assertThat(page.locator("#hangar-selection-count")).hasText("2");

        boolean assigned = applyOtherUnit(page);
        assertThat(bar).isHidden();
        assertUnit(page, first, assigned);
        assertUnit(page, second, assigned);

        row(page, first).getByTestId("hangar-ship-check").check();
        row(page, second).getByTestId("hangar-ship-check").check();
        boolean reassigned = applyOtherUnit(page);
        assertEquals(!assigned, reassigned, "the second action must flip the unit back");
        assertUnit(page, first, reassigned);
        assertUnit(page, second, reassigned);

        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the bulk unit change must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "hangar-bulk-unit");
        throw failure;
      }
    }
  }

  /**
   * Adds a ship of the seeded type with a name through the ship dialog and waits for its row.
   *
   * @param page the hangar page
   * @param name the ship's name
   */
  private static void addShip(Page page, String name) {
    page.getByTestId("hangar-add-ship").click();
    page.locator("#ship-type").selectOption(new SelectOption().setLabel("E2E Ship Type"));
    page.locator("#ship-name").fill(name);
    page.getByTestId("segment-insuranceKind-lti").click();
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/hangar/add") && "POST".equals(r.request().method()),
        () -> page.getByTestId("hangar-ship-submit").click());
    assertThat(row(page, name)).isVisible();
  }

  /**
   * Opens the bulk unit dialog from the selection bar, picks the other choice than the preset one —
   * „Keine Einheit" for ships with a unit, the first unit for ships without — and saves.
   *
   * @param page the hangar page
   * @return whether the ships now have a unit
   */
  private static boolean applyOtherUnit(Page page) {
    page.getByTestId("hangar-bulk-unit-open").click();
    assertThat(page.getByTestId("hangar-unit-bulk-message")).isVisible();
    Locator select = page.getByTestId("hangar-unit-select");
    boolean assign = select.inputValue().isEmpty();
    select.selectOption(assign ? new SelectOption().setIndex(1) : new SelectOption().setValue(""));
    page.waitForResponse(
        r -> r.url().contains("/hangar/bulk-org-unit") && "POST".equals(r.request().method()),
        () -> page.getByTestId("hangar-unit-submit").click());
    return assign;
  }

  /**
   * Asserts whether a ship's row shows a unit badge.
   *
   * @param page the hangar page
   * @param name the ship's name
   * @param assigned whether the ship is expected to have a unit
   */
  private static void assertUnit(Page page, String name, boolean assigned) {
    if (assigned) {
      assertThat(unitBadge(page, name)).isVisible();
    } else {
      assertThat(unitBadge(page, name)).isHidden();
    }
  }

  /**
   * Finds the list row of a ship by its name.
   *
   * @param page the hangar page
   * @param name the ship's name
   * @return the row locator
   */
  private static Locator row(Page page, String name) {
    return page.getByTestId("hangar-ship-row").filter(new Locator.FilterOptions().setHasText(name));
  }

  /**
   * Finds the unit badge of a ship's row; a ship without a unit shows only the muted dash.
   *
   * @param page the hangar page
   * @param name the ship's name
   * @return the badge locator
   */
  private static Locator unitBadge(Page page, String name) {
    return row(page, name)
        .getByTestId("hangar-ship-unit")
        .locator(".squadron-badge:not(.squadron-badge-muted)");
  }
}
