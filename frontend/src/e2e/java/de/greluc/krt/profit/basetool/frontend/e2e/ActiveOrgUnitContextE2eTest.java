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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The active org-unit context as a member of a Staffel and an SK sees it (REQ-ORG-024, REQ-ORG-031,
 * REQ-ORG-032): without a choice the switcher reads „Alle meine Org-Einheiten" and the Lager lists
 * both units, a chosen Staffel or SK stays selected, narrows the Lager to it and survives a new
 * login, and a switch in one tab reloads the browser's other tabs.
 */
@Tag("e2e")
class ActiveOrgUnitContextE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String BOTH_USER = "test-both";
  private static final String BOTH_PASSWORD = "test-both-pw";

  private static final String SWITCHER = "#squadron-switcher-select";
  private static final String LAGER = "/inventory?storage=all";
  private static final String ACTIVE_CHOICE = "/api/v1/org-units/me/active-choice";
  private static final int SEED_QUALITY = 640;
  private static final double TIMEOUT_MILLIS = 20_000;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String staffelId;
  private static String skId;
  private static String staffelMaterialId;
  private static String skMaterialId;

  /**
   * Launches the browser and (ephemeral stack only) puts {@code test-both} into a new Staffel and a
   * new SK, with one Lager row in each, starting from no stored choice.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    String bothId = seeder.getUserId(BOTH_USER, BOTH_PASSWORD);
    staffelId = seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Context Staffel", "ECST");
    skId = seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Context SK", "ECSK");
    seeder.assignStaffelMembership(ADMIN_USER, ADMIN_PASSWORD, bothId, staffelId, false, false);
    seeder.addSpecialCommandMember(ADMIN_USER, ADMIN_PASSWORD, skId, bothId);
    String locationId = seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E Context Loc");
    staffelMaterialId =
        seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Context Mat Staffel");
    skMaterialId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Context Mat SK");
    seeder.createInventoryItemOwnedBy(
        BOTH_USER, BOTH_PASSWORD, staffelMaterialId, locationId, SEED_QUALITY, 10, staffelId);
    seeder.createInventoryItemOwnedBy(
        BOTH_USER, BOTH_PASSWORD, skMaterialId, locationId, SEED_QUALITY, 10, skId);
    clearStoredChoice();
  }

  /** Leaves {@code test-both} without a stored choice for the suites that run after this one. */
  @AfterAll
  static void tearDown() {
    if (seeder != null) {
      clearStoredChoice();
    }
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** The unit warehouse shows the shared notice and both units' stock until a Staffel is selected. */
  @Test
  void withoutAChoiceTheLagerShowsBothUnitsUnderAllMyOrgUnits() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, STACK.baseUrl(), BOTH_USER, BOTH_PASSWORD);
        E2eSupport.navigate(page, STACK.baseUrl() + LAGER);
        assertThat(page.locator(SWITCHER)).hasValue("");
        assertThat(page.getByTestId("all-my-org-units-notice")).isVisible(visible());
        assertThat(group(page, staffelMaterialId)).isVisible(visible());
        assertThat(group(page, skMaterialId)).isVisible(visible());

        pin(page, staffelId);
        E2eSupport.navigate(page, STACK.baseUrl() + LAGER);
        assertThat(page.locator(SWITCHER)).hasValue(staffelId);
        assertThat(group(page, staffelMaterialId)).isVisible(visible());
        assertThat(group(page, skMaterialId)).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "active-org-unit-context-union");
        throw failure;
      } finally {
        clearStoredChoice();
      }
    }
  }

  /**
   * A chosen SK stays selected instead of falling back to the Staffel, the Lager then lists only
   * the SK's row, and „Alle meine Org-Einheiten" can be chosen again afterwards.
   */
  @Test
  void aChosenSpecialCommandStaysSelectedAndNarrowsTheLager() {
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, STACK.baseUrl(), BOTH_USER, BOTH_PASSWORD);
        E2eSupport.navigate(page, STACK.baseUrl() + LAGER);
        pin(page, skId);
        E2eSupport.navigate(page, STACK.baseUrl() + LAGER);
        assertThat(page.locator(SWITCHER)).hasValue(skId);
        assertThat(group(page, skMaterialId)).isVisible(visible());
        assertThat(group(page, staffelMaterialId)).hasCount(0);

        pin(page, "");
        E2eSupport.navigate(page, STACK.baseUrl() + LAGER);
        assertThat(page.locator(SWITCHER)).hasValue("");
        assertThat(group(page, staffelMaterialId)).isVisible(visible());
        assertThat(group(page, skMaterialId)).isVisible(visible());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "active-org-unit-context-special-command");
        throw failure;
      } finally {
        clearStoredChoice();
      }
    }
  }

  /** A chosen unit is restored at the next login, and a switch in one tab reloads the other. */
  @Test
  void aChoiceSurvivesANewLoginAndReachesTheOtherTabs() {
    try (BrowserContext first = newContext()) {
      Page page = first.newPage();
      E2eSupport.login(page, STACK.baseUrl(), BOTH_USER, BOTH_PASSWORD);
      pin(page, staffelId);
    }
    try (BrowserContext second = newContext()) {
      Page tabA = second.newPage();
      Page tabB = null;
      try {
        E2eSupport.login(tabA, STACK.baseUrl(), BOTH_USER, BOTH_PASSWORD);
        E2eSupport.navigate(tabA, STACK.baseUrl() + LAGER);
        assertThat(tabA.locator(SWITCHER)).hasValue(staffelId);
        assertThat(group(tabA, skMaterialId)).hasCount(0);

        tabB = second.newPage();
        E2eSupport.navigate(tabB, STACK.baseUrl() + LAGER);
        pin(tabB, "");

        assertThat(tabA.locator(SWITCHER))
            .hasValue("", new LocatorAssertions.HasValueOptions().setTimeout(TIMEOUT_MILLIS));
        assertThat(group(tabA, skMaterialId)).isVisible(visible());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(tabA, "active-org-unit-context-tab-a");
        if (tabB != null) {
          E2eSupport.dump(tabB, "active-org-unit-context-tab-b");
        }
        throw failure;
      } finally {
        clearStoredChoice();
      }
    }
  }

  private static BrowserContext newContext() {
    return browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
  }

  private static LocatorAssertions.IsVisibleOptions visible() {
    return new LocatorAssertions.IsVisibleOptions().setTimeout(TIMEOUT_MILLIS);
  }

  private static Locator group(Page page, String materialId) {
    return page.locator("div.tree-row--group[data-material-id='" + materialId + "']");
  }

  /**
   * Switches the active org unit through the sidebar switcher's form and waits for the switch to be
   * answered; an empty id chooses „Alle meine Org-Einheiten".
   *
   * @param page a page that renders the sidebar
   * @param orgUnitId the org unit to choose, or {@code ""}
   */
  private static void pin(Page page, String orgUnitId) {
    page.waitForResponse(
        response -> response.url().contains("/me/active-org-unit"),
        () ->
            page.evaluate(
                "(id) => { const s = document.getElementById('squadron-switcher-select'); s.value ="
                    + " id; document.getElementById('squadron-switcher-form').submit(); }",
                orgUnitId));
  }

  /** Stores „Alle meine Org-Einheiten" as {@code test-both}'s choice through the backend. */
  private static void clearStoredChoice() {
    seeder.putForStatus(BOTH_USER, BOTH_PASSWORD, ACTIVE_CHOICE, "{\"orgUnitId\":null}");
  }
}
