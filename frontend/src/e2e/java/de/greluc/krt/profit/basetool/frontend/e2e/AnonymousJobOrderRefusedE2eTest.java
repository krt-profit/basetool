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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Nobody raises a job order without a login.
 *
 * <p>This class replaces {@code AnonymousJobOrderE2eTest}, which exercised the public request form:
 * an outsider filled {@code /orders/create}, and because the order had no author it was stamped
 * onto a configured intake Spezialkommando. That form is gone (ADR-0149), the setting with it
 * (V234), and the four tests that pinned the behaviour describe a product that no longer exists.
 *
 * <p>What is left is worth pinning precisely because it is a *removal*: a feature that is merely
 * un-linked comes back the first time somebody re-adds a route. Both halves are asserted — the page
 * a visitor would reach, and the endpoint a script would call — because they are enforced in
 * different places (the frontend's filter chain and the backend's) and either could be relaxed
 * without the other noticing.
 */
@Tag("e2e")
class AnonymousJobOrderRefusedE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** What an unauthenticated API caller must get. */
  private static final int UNAUTHORIZED = 401;

  private static Playwright playwright;
  private static Browser browser;

  /** A job-order material, so the refused payload is well-formed rather than merely invalid. */
  private static String materialId;

  /** Starts Playwright and seeds the one material the refused payload names. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      materialId =
          new BackendSeeder().ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Refused Mat");
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
   * A visitor with no session does not get the create form.
   *
   * <p>Asserted as "not the form" rather than as a particular status: the frontend answers with a
   * redirect into the OAuth2 flow, and whether that lands on a Keycloak page or an interstitial is
   * a detail of the SSO setup, not of this rule.
   */
  @Test
  void anonymousVisitorDoesNotReachTheCreateForm() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");

        assertNotEquals(
            baseUrl + "/orders/create",
            page.url(),
            "an anonymous visitor must be sent away from the create form, not shown it");
        assertEquals(
            0,
            page.locator("#handle").count(),
            "the create form's contact field must not render for an anonymous visitor");
      } finally {
        page.close();
      }
    }
  }

  /**
   * The endpoint refuses an unauthenticated caller, whatever the page does.
   *
   * <p>The payload is complete and would have been accepted before ADR-0149, so the 401 is about
   * the missing credential and nothing else.
   */
  @Test
  void anonymousApiCreateIsRefused() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded material");
    int status =
        new BackendSeeder()
            .anonymousCreateMaterialOrderStatus(
                IRIDIUM_ID, "E2E Refused " + UUID.randomUUID(), materialId, 650, 10);

    assertEquals(UNAUTHORIZED, status, "POST /api/v1/orders must refuse an anonymous caller");
  }
}
