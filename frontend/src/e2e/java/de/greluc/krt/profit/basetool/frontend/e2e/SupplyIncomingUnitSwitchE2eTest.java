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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The unit switch of „Eingehende Spenden" (REQ-FE-001, REQ-FE-010): choosing another receiving unit
 * swaps the list in place, moves the page to that unit's live room and keeps the address in step,
 * without a reload.
 */
@Tag("e2e")
class SupplyIncomingUnitSwitchE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String otherUnitId;

  /** Launches the browser, switches the supply surface on and lets two units take donations. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    }
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    otherUnitId = seeder.createSquadron(USERNAME, PASSWORD, "Unit Switch " + suffix, "US" + suffix);
    int settings =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/settings",
            "{\"enabled\":true,\"governingAreaId\":null}");
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    for (String unitId : new String[] {IRIDIUM_ID, otherUnitId}) {
      int unit =
          seeder.putForStatus(
              USERNAME,
              PASSWORD,
              "/api/v1/supply/admin/units/" + unitId,
              "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
      assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
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

  /** Switches the unit of the incoming donations and checks the in-place update. */
  @Test
  void switchingTheUnitUpdatesThePageInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/supply/donations/incoming?orgUnitId=" + IRIDIUM_ID);
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        assertThat(page.locator("#supply-incoming-results"))
            .hasAttribute("data-unit-id", IRIDIUM_ID);

        page.locator("#supply-incoming-unit").selectOption(otherUnitId);

        assertThat(page.locator("#supply-incoming-results"))
            .hasAttribute(
                "data-unit-id",
                otherUnitId,
                new LocatorAssertions.HasAttributeOptions().setTimeout(10_000));
        assertThat(page.locator("#supply-incoming-results"))
            .hasAttribute("data-live-sync-topic", "supply-donations:" + otherUnitId);
        assertTrue(
            page.url().contains("orgUnitId=" + otherUnitId),
            "the address must follow the unit: " + page.url());
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the unit switch must update the page in place — no reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-incoming-unit-switch");
        throw failure;
      }
    }
  }
}
