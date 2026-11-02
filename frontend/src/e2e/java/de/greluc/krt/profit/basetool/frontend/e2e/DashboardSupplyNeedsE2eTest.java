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
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The „Bedarf &amp; Spenden" dashboard widget with the supply feature on (REQ-UI-034): the union of
 * the caller's own units without a pin, the pinned unit of the active context, and the unit chosen
 * in the widget's options, which wins over the pin. An administrator runs it because only an
 * account without a home unit pin reaches the unpinned path.
 */
@Tag("e2e")
class DashboardSupplyNeedsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String RESET_DASHBOARD =
      """
      async () => {
        const board = document.getElementById('dashboard-board');
        await window.krtFetch.write({
          method: 'DELETE',
          url: '/dashboard/member?version=' + board.dataset.version,
          toast: false,
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path adminState;
  private static String otherUnitId;
  private static String iridiumMaterial;
  private static String otherMaterial;

  /** Switches supply on, lets two units raise needs and gives each one open need. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(ADMIN, ADMIN_PASSWORD);
    }
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    otherUnitId =
        seeder.createSquadron(ADMIN, ADMIN_PASSWORD, "Bedarf Widget " + suffix, "BW" + suffix);
    seeder.assignSecondStaffelleiterSeat(
        ADMIN, ADMIN_PASSWORD, seeder.getUserId(ADMIN, ADMIN_PASSWORD), IRIDIUM_ID, otherUnitId);
    int settings =
        seeder.putForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/supply/admin/settings",
            "{\"enabled\":true,\"governingAreaIds\":[]}");
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    iridiumMaterial = "Widget Ore I " + suffix;
    otherMaterial = "Widget Ore O " + suffix;
    raiseNeed(seeder, IRIDIUM_ID, iridiumMaterial);
    raiseNeed(seeder, otherUnitId, otherMaterial);
    adminState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN, ADMIN_PASSWORD);
  }

  /**
   * Drops the second Staffel again so later suites see the administrator in one unit, then releases
   * the browser.
   */
  @AfterAll
  static void tearDown() {
    if (STACK.managesStack() && otherUnitId != null) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.assignStaffelMembership(
          ADMIN, ADMIN_PASSWORD, seeder.getUserId(ADMIN, ADMIN_PASSWORD), IRIDIUM_ID, false, false);
    }
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** Leaves the administrator unpinned with the default dashboard. */
  @BeforeEach
  void restoreTheDefault() {
    run(
        "supply-widget-restore",
        page -> {
          pinOrgUnit(page, "");
          page.evaluate(RESET_DASHBOARD);
        });
  }

  /** Without a pin the widget lists the needs of every own unit and names the unit per row. */
  @Test
  void withoutAPinTheWidgetUnionsTheOwnUnits() {
    run(
        "supply-widget-union",
        page -> {
          addTheWidget(page);

          Locator widget = page.getByTestId("dashboard-widget-supply-needs");
          assertThat(
                  widget.getByTestId("dashboard-supply-needs-row").filter(hasText(iridiumMaterial)))
              .hasCount(1);
          assertThat(
                  widget.getByTestId("dashboard-supply-needs-row").filter(hasText(otherMaterial)))
              .hasCount(1);
          assertThat(widget.getByTestId("dashboard-supply-needs-unit").first()).isVisible();
        });
  }

  /** A pinned unit narrows the widget to that unit and drops the per-row unit name. */
  @Test
  void aPinnedContextNarrowsTheWidgetToThatUnit() {
    run(
        "supply-widget-pinned",
        page -> {
          addTheWidget(page);
          try {
            pinOrgUnit(page, otherUnitId);
            E2eSupport.navigate(page, STACK.baseUrl() + "/");

            Locator widget = page.getByTestId("dashboard-widget-supply-needs");
            assertThat(
                    widget.getByTestId("dashboard-supply-needs-row").filter(hasText(otherMaterial)))
                .hasCount(1);
            assertThat(
                    widget
                        .getByTestId("dashboard-supply-needs-row")
                        .filter(hasText(iridiumMaterial)))
                .hasCount(0);
            assertThat(widget.getByTestId("dashboard-supply-needs-unit")).hasCount(0);
            assertThat(widget.getByTestId("dashboard-supply-needs-row").first())
                .hasAttribute("href", "/supply/needs?orgUnitId=" + otherUnitId);
          } finally {
            pinOrgUnit(page, "");
          }
        });
  }

  /** The unit chosen in the options narrows the widget to that unit and survives a reload. */
  @Test
  void theChosenUnitNarrowsTheWidget() {
    run(
        "supply-widget-chosen",
        page -> {
          addTheWidget(page);
          chooseUnit(page, IRIDIUM_ID);

          Locator widget = page.getByTestId("dashboard-widget-supply-needs");
          assertThat(widget).hasAttribute("data-unit", IRIDIUM_ID);
          assertThat(
                  widget.getByTestId("dashboard-supply-needs-row").filter(hasText(iridiumMaterial)))
              .hasCount(1);
          assertThat(
                  widget.getByTestId("dashboard-supply-needs-row").filter(hasText(otherMaterial)))
              .hasCount(0);

          page.reload();
          assertThat(page.getByTestId("dashboard-widget-supply-needs"))
              .hasAttribute("data-unit", IRIDIUM_ID);
          assertThat(
                  page.getByTestId("dashboard-widget-supply-needs")
                      .getByTestId("dashboard-supply-needs-row")
                      .filter(hasText(iridiumMaterial)))
              .hasCount(1);
        });
  }

  /** A pin on another unit than the chosen one makes the widget unavailable instead of mixing. */
  @Test
  void aPinOnAnotherUnitThanTheChosenOneMakesTheWidgetUnavailable() {
    run(
        "supply-widget-unavailable",
        page -> {
          addTheWidget(page);
          chooseUnit(page, IRIDIUM_ID);
          try {
            pinOrgUnit(page, otherUnitId);

            Locator widget = page.getByTestId("dashboard-widget-supply-needs");
            assertThat(widget.getByTestId("dashboard-widget-unavailable")).isVisible();
            assertThat(widget.getByTestId("dashboard-supply-needs-row")).hasCount(0);

            pinOrgUnit(page, IRIDIUM_ID);
            assertThat(
                    page.getByTestId("dashboard-widget-supply-needs")
                        .getByTestId("dashboard-supply-needs-row")
                        .filter(hasText(iridiumMaterial)))
                .hasCount(1);
          } finally {
            pinOrgUnit(page, "");
          }
        });
  }

  /** Opens the options of the widget in edit mode and stores the unit. */
  private static void chooseUnit(Page page, String unitId) {
    page.getByTestId("dashboard-widget-supply-needs").getByTestId("dashboard-options").click();
    page.locator("#dashboard-option-unit").selectOption(unitId);
    page.getByTestId("dashboard-options-save").click();
  }

  private static void raiseNeed(BackendSeeder seeder, String unitId, String materialName) {
    String materialId = seeder.ensureJobOrderMaterial(ADMIN, ADMIN_PASSWORD, materialName);
    int unit =
        seeder.putForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/supply/admin/units/" + unitId,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
    int raised =
        seeder.postForStatus(
            ADMIN,
            ADMIN_PASSWORD,
            "/api/v1/supply/needs",
            "{\"orgUnitId\":\""
                + unitId
                + "\",\"materialId\":\""
                + materialId
                + "\",\"amount\":20,\"priority\":\"NORMAL\",\"purchasable\":false}");
    assertEquals(201, raised, "a need must be raisable");
  }

  private static Locator.FilterOptions hasText(String text) {
    return new Locator.FilterOptions().setHasText(text);
  }

  /** Adds the widget through the catalogue and waits for its body. */
  private static void addTheWidget(Page page) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/");
    page.getByTestId("dashboard-customise").click();
    page.getByTestId("dashboard-add").click();
    page.getByTestId("dashboard-add-supply-needs").click();
    assertThat(page.getByTestId("dashboard-widget-supply-needs")).hasCount(1);
    assertThat(
            page.getByTestId("dashboard-widget-supply-needs")
                .locator(
                    "[data-testid='dashboard-supply-needs-row'],"
                        + " [data-testid='dashboard-supply-needs-empty']")
                .first())
        .isVisible();
  }

  /**
   * Pins the session's active org unit through the sidebar switcher's form, waits for the POST to
   * be answered and reopens the dashboard; an empty id returns to the all-units view.
   */
  private static void pinOrgUnit(Page page, String orgUnitId) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/");
    page.waitForResponse(
        response -> response.url().contains("/me/active-org-unit"),
        () ->
            page.evaluate(
                "(id) => { const s = document.getElementById('squadron-switcher-select'); s.value ="
                    + " id; document.getElementById('squadron-switcher-form').submit(); }",
                orgUnitId));
    E2eSupport.navigate(page, STACK.baseUrl() + "/");
  }

  private static void run(String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(adminState)
                .setViewportSize(1440, 900))) {
      Page page = context.newPage();
      try {
        scenario.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
