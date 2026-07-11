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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional coverage for the ADMIN member-management edit page ({@code /members/{id}/edit}), whose
 * page-level in-place save had no end-to-end test — {@code RoleAppointmentMatrixE2eTest} exercises
 * only the backend appointment API, and {@code RolePermissionsE2eTest} only {@code sec:authorize}
 * gating on the orders view.
 *
 * <p>It saves a member's rank through the in-place form (REQ-FE-007: {@code POST
 * /members/{id}/edit} with {@code X-Requested-With} → backend {@code PUT
 * /api/v1/users/{id}/attributes}), which runs through {@code window.krtFetch} without a page
 * reload, and asserts the write landed: no error toast, no optimistic-lock reload-confirm dialog,
 * the no-reload marker survived, and the backend persisted the new rank.
 *
 * <p>Actor: {@code test-admin} (the {@code /members} controller is {@code ADMIN}-gated). The edited
 * user is {@code test-member}, materialised in the backend via {@code getUserId} so the edit page
 * resolves. Rank is a top-level attribute editable regardless of squadron membership, so the test
 * mutates no membership state that sibling suites depend on. Tagged {@code @Tag("e2e")}: it mutates
 * data, so it runs only against the ephemeral stack.
 */
@Tag("e2e")
class MemberEditInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  /** The rank value the test assigns and then reads back from the backend. */
  private static final int TARGET_RANK = 7;

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String memberUserId;

  /**
   * Launches the browser, materialises {@code test-member} in the backend (so the edit page
   * resolves) and, for the ephemeral stack, ensures the admin's own {@code app_user} row exists,
   * then captures one authenticated admin session reused across the test.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      memberUserId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN_USER, ADMIN_PASSWORD);
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
   * Opens the member's edit page, changes the rank and saves in place, asserting the success UX (no
   * error toast, no reload-confirm dialog, no page reload) and that the backend persisted the new
   * rank.
   */
  @Test
  void savesMemberRankInPlace() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/members/" + memberUserId + "/edit?source=members");
        page.waitForLoadState();
        assertThat(page.locator("#member-edit-form")).isVisible();

        // Marker on the live document: a full navigation/reload wipes it, so its survival proves
        // the
        // save stayed in place. The position:fixed footer can cover the bottom submit button.
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        page.locator("#member-edit-form select[name='rank']")
            .selectOption(String.valueOf(TARGET_RANK));
        page.waitForResponse(
            response ->
                response.url().contains("/members/" + memberUserId + "/edit")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#member-edit-form button[type='submit']").click());

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the member save must update in place — no page reload cleared the marker");
        assertEquals(TARGET_RANK, persistedRank(), "the in-place save must persist the new rank");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-edit-in-place");
        throw failure;
      }
    }
  }

  // ---------------------------------------------------------------------- helpers --

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused admin storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Reads the edited member's persisted rank straight from the backend ({@code GET
   * /api/v1/users/{id}}), so the persistence assertion does not race the client's in-place update.
   *
   * @return the persisted rank as an int
   */
  private static int persistedRank() {
    String body =
        new BackendSeeder().getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/users/" + memberUserId);
    return JsonParser.parseString(body).getAsJsonObject().get("rank").getAsInt();
  }
}
