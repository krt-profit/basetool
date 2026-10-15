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
 * An admin opens a member's hangar from the member edit page, adds, edits and deletes a ship there
 * and changes the unit of one and of several ships, each in place (REQ-HANGAR-011, REQ-HANGAR-012,
 * REQ-FE-001).
 */
@Tag("e2e")
class MemberHangarE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static String memberId;

  /** Launches the browser and resolves the member whose hangar the admin keeps. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    }
    memberId = seeder.getUserId(USERNAME, PASSWORD);
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
   * The member edit page links to the member hangar; a ship added there appears in the list, its
   * renamed version replaces it, and its deletion removes it, without a reload.
   */
  @Test
  void addsEditsAndDeletesAShipOfTheMemberInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/members/" + memberId + "/edit");
        page.getByTestId("member-edit-hangar").click();
        page.waitForURL("**/admin/members/" + memberId + "/hangar");
        hideFooter(page);
        page.evaluate("window.__krtNoReload = true;");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "Admin Kept " + suffix;
        String renamed = "Admin Renamed " + suffix;

        addShip(page, name);

        row(page, name).getByTestId("member-hangar-edit").click();
        page.getByTestId("member-hangar-name").fill(renamed);
        submit(page, "/update");
        assertThat(row(page, renamed)).isVisible();
        assertThat(row(page, name)).hasCount(0);

        row(page, renamed).getByTestId("member-hangar-edit").click();
        page.getByTestId("member-hangar-delete").click();
        page.waitForResponse(
            r -> r.url().contains("/delete") && "POST".equals(r.request().method()),
            () -> page.getByTestId("member-hangar-delete-confirm").click());
        assertThat(row(page, renamed)).hasCount(0);

        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the member hangar writes must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-hangar");
        throw failure;
      }
    }
  }

  /**
   * The admin moves one ship of the member with the row action and then two ships with the
   * selection bar; the unit column follows each change without a reload (REQ-HANGAR-012).
   */
  @Test
  void changesTheUnitOfOneAndOfSeveralShipsInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/members/" + memberId + "/hangar");
        hideFooter(page);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String first = "Admin Unit A " + suffix;
        String second = "Admin Unit B " + suffix;
        addShip(page, first);
        addShip(page, second);
        page.evaluate("window.__krtNoReload = true;");

        row(page, first).getByTestId("member-hangar-change-unit").click();
        boolean assigned = applyOtherUnit(page, "/org-unit");
        assertUnit(page, first, assigned);

        Locator bar = page.getByTestId("member-hangar-selection-bar");
        assertThat(bar).isHidden();
        row(page, first).getByTestId("member-hangar-check").check();
        row(page, second).getByTestId("member-hangar-check").check();
        assertThat(bar).isVisible();
        assertThat(page.locator("#member-hangar-selection-count")).hasText("2");
        page.getByTestId("member-hangar-bulk-unit-open").click();
        assertThat(page.getByTestId("member-hangar-unit-bulk-message")).isVisible();
        boolean bulkAssigned = applyOtherUnit(page, "/bulk-org-unit");
        assertThat(bar).isHidden();
        assertUnit(page, first, bulkAssigned);
        assertUnit(page, second, bulkAssigned);

        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the member hangar unit changes must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-hangar-unit");
        throw failure;
      }
    }
  }

  /**
   * Adds a ship of the seeded type with a name through the member's ship dialog.
   *
   * @param page the member hangar page
   * @param name the ship's name
   */
  private static void addShip(Page page, String name) {
    page.getByTestId("member-hangar-add").click();
    page.getByTestId("member-hangar-type")
        .selectOption(new SelectOption().setLabel("E2E Ship Type"));
    page.getByTestId("member-hangar-name").fill(name);
    page.getByTestId("segment-insuranceKind-lti").click();
    Locator owner = page.locator("#member-ship-owner-picker-slot select");
    if (owner.count() > 0 && owner.isVisible()) {
      owner.selectOption(new SelectOption().setIndex(owner.inputValue().isEmpty() ? 1 : 0));
    }
    submit(page, "/hangar/add");
    assertThat(row(page, name)).isVisible();
  }

  /**
   * Picks the other choice than the unit dialog's preset — „Keine Einheit" for a ship with a unit,
   * the first unit for one without — and saves.
   *
   * @param page the member hangar page
   * @param urlPart a part of the write's URL
   * @return whether the ships now have a unit
   */
  private static boolean applyOtherUnit(Page page, String urlPart) {
    Locator select = page.getByTestId("member-hangar-unit-select");
    boolean assign = select.inputValue().isEmpty();
    select.selectOption(assign ? new SelectOption().setIndex(1) : new SelectOption().setValue(""));
    page.waitForResponse(
        r -> r.url().contains(urlPart) && "POST".equals(r.request().method()),
        () -> page.getByTestId("member-hangar-unit-submit").click());
    return assign;
  }

  /**
   * Asserts whether a ship's row shows a unit badge.
   *
   * @param page the member hangar page
   * @param name the ship's name
   * @param assigned whether the ship is expected to have a unit
   */
  private static void assertUnit(Page page, String name, boolean assigned) {
    Locator badge =
        row(page, name)
            .getByTestId("member-hangar-unit")
            .locator(".squadron-badge:not(.squadron-badge-muted)");
    if (assigned) {
      assertThat(badge).isVisible();
    } else {
      assertThat(badge).isHidden();
    }
  }

  /**
   * Submits the ship dialog and waits for the write it sends.
   *
   * @param page the member hangar page
   * @param urlPart a part of the write's URL
   */
  private static void submit(Page page, String urlPart) {
    page.waitForResponse(
        r -> r.url().contains(urlPart) && "POST".equals(r.request().method()),
        () -> page.getByTestId("member-hangar-submit").click());
  }

  /**
   * Hides the fixed footer, which can cover the dialog's buttons on a short viewport.
   *
   * @param page the member hangar page
   */
  private static void hideFooter(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
  }

  /**
   * Finds the list row of a ship by its name.
   *
   * @param page the member hangar page
   * @param name the ship's name
   * @return the row locator
   */
  private static Locator row(Page page, String name) {
    return page.getByTestId("member-hangar-row")
        .filter(new Locator.FilterOptions().setHasText(name));
  }
}
