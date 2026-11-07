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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E regression test: deleting the last material alias in place on {@code /admin/material-aliases}
 * restores the "no entries" placeholder without a reload (REQ-FE-001), and the success toast it
 * shows lets the pointer through to what it covers (REQ-UI-008).
 *
 * <p>Relies on the ephemeral stack starting with no material alias, which a fresh database has
 * (V108 seeds aliases only for materials that exist); runs as {@code test-admin}.
 */
@Tag("e2e")
class MaterialAliasEmptyStateInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The backend path the alias is seeded through. */
  private static final String ALIASES_API = "/api/v1/material-external-aliases";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the admin user's IRIDIUM membership.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
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
   * Seeds one alias, deletes it in place, and asserts that the shown success toast takes no pointer
   * events and that the empty-state placeholder reappears without a reload.
   */
  @Test
  void deletingTheLastAliasRestoresTheEmptyStateInPlace() {
    String suffix = UUID.randomUUID().toString();
    String materialId =
        seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Alias Material " + suffix);
    String aliasId =
        seeder.seedEntity(
            USERNAME,
            PASSWORD,
            ALIASES_API,
            "{\"materialId\":\""
                + materialId
                + "\",\"sourceSystem\":\"UEX\",\"externalName\":\"E2E Alias "
                + suffix
                + "\"}");
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/material-aliases");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        Locator row = page.locator("tr[data-alias-id='" + aliasId + "']");
        assertThat(row).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("[data-alias-empty]")).isHidden();

        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/material-aliases/" + aliasId + "/delete")
                    && "POST".equals(response.request().method()),
            () -> row.locator("form[data-alias-delete] button[type='submit']").click());

        Locator toast = page.locator(".notification-toast:not(.error-toast)");
        assertThat(toast).hasCount(1);
        assertEquals(
            "none",
            toast.evaluate(
                "shown => { shown.classList.add('visible'); return"
                    + " getComputedStyle(shown).pointerEvents; }"),
            "a shown toast must not take pointer events, or it swallows clicks on what it covers");
        assertThat(page.locator("[data-alias-empty]"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(page.locator("tr[data-alias-id]")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "deleting the last alias must restore the empty state in place — no full reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-alias-empty-state");
        throw failure;
      }
    }
  }
}
