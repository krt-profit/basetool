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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Two-context live-sync coverage for the Kartellbank staff request queue (#1102, REQ-FE-015 /
 * ADR-0094): a staff decision one bank employee makes on {@code /bank/requests} must update another
 * employee's queue without a manual reload — the {@code requestQueue} section key crossing the
 * global {@code bank} room.
 *
 * <p>Two browser contexts as the same bank employee are two distinct {@code /ws/sync} sockets, so
 * the deterministic pre-mutation wait is {@code window.krtLiveSync.subscribedTopics()} becoming
 * non-empty (the {@code bank} staff room is authorized by a local role check against the
 * handshake-captured authorities). A pending request is seeded through the backend; context A
 * <em>rejects</em> it (which books nothing and needs no holder, yet still broadcasts {@code
 * bank/[requestQueue,grid]}) and context B — a passive viewer — must drop the now-decided request
 * from its default pending-only queue in place.
 */
@Tag("e2e")
class BankRequestsLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";

  /** The canonical IRIDIUM Squadron seeded at stack bootstrap (the request account's org unit). */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String accountId;

  /**
   * Launches the browser and seeds an org-unit account plus the employee's grant on it, so a
   * pending request on it appears in the employee's staff queue.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    // The officer oversees IRIDIUM (so they may raise a request against its account).
    String officerId = seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
    seeder.assignStaffelMembership(
        ADMIN_USER, ADMIN_PASSWORD, officerId, IRIDIUM_SQUADRON_ID, false, false);

    accountId =
        seeder.ensureOrgUnitBankAccount(
            MGMT_USER, MGMT_PASSWORD, "E2E Requests Sync Account", IRIDIUM_SQUADRON_ID);

    // The employee may act on the account, so its pending requests show in their staff queue.
    // Idempotent: an org-unit account already auto-grants some members, so a plain create would 409
    // "already holds a grant"; either way the employee ends up able to act on the account.
    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    seeder.ensureBankGrant(MGMT_USER, MGMT_PASSWORD, employeeId, accountId, true, true, false);
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
   * Context A (a bank employee) rejects a pending request from the staff queue; context B — a
   * passive employee viewer that never reloads — must drop that request from its pending-only queue
   * IN PLACE, driven purely by the change signal over {@code /ws/sync}.
   */
  @Test
  void rejectByOneEmployeePropagatesToAnotherEmployeeLive() {
    String baseUrl = STACK.baseUrl();
    String requestId =
        seeder.raiseBankDepositRequest(OFFICER_USER, OFFICER_PASSWORD, accountId, 8001);
    String rejectBtn =
        "[data-testid='bank-request-reject-btn'][data-field-_id='" + requestId + "']";

    Path storageState =
        E2eSupport.authenticatedStorageState(browser, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    try (BrowserContext contextA =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState));
        BrowserContext contextB =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState))) {
      Page pageA = contextA.newPage();
      Page pageB = contextB.newPage();
      try {
        E2eSupport.navigate(pageA, baseUrl + "/bank/requests");
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/bank/requests");
        pageB.waitForLoadState();

        // Both employees see the pending request initially.
        assertThat(pageB.locator(rejectBtn))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));

        // A full reload on B would clear this marker; the live in-place swap leaves it intact.
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: B is registered with the relay once its `bank` subscribe is acked.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        // Context A rejects the request with a reason (books nothing; broadcasts requestQueue).
        pageA.locator(rejectBtn).click(new Locator.ClickOptions().setTimeout(20_000));
        pageA.locator("[data-testid='bank-reject-reason']").fill("E2E live-sync rejection");
        dropFooter(pageA);
        pageA.waitForResponse(
            r ->
                r.url().contains("/api/proxy/bank/requests/" + requestId + "/reject")
                    && "POST".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> pageA.locator("[data-testid='bank-reject-submit']").click());

        // The assertion under test: context B — which did nothing — drops the now-rejected request
        // from its default pending-only queue in place (the global bank room coalesces at ~1.5 s).
        assertThat(pageB.locator(rejectBtn))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(30_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "bank-requests-livesync-a");
        E2eSupport.dump(pageB, "bank-requests-livesync-b");
        throw failure;
      }
    }
  }

  /**
   * Regression guard for the bank decision-modal reopen bug: after a bank employee decides one
   * request, the shared decision modal must re-open for the very next request in the same session —
   * without a page reload. The success path once closed the modal by writing an inline {@code
   * style.display = 'none'}, which outranks the class-based {@code open-modal-display} re-open (a
   * modal is shown via the {@code krtm-modal-open} class since the CSP class migration, ADR-0093),
   * so the second request's button silently did nothing until the page was reloaded (REQ-UI-013).
   */
  @Test
  void decidingOneRequestReopensTheModalForTheNextInPlace() {
    String baseUrl = STACK.baseUrl();
    String firstId =
        seeder.raiseBankDepositRequest(OFFICER_USER, OFFICER_PASSWORD, accountId, 8101);
    String secondId =
        seeder.raiseBankDepositRequest(OFFICER_USER, OFFICER_PASSWORD, accountId, 8102);
    String firstRejectBtn =
        "[data-testid='bank-request-reject-btn'][data-field-_id='" + firstId + "']";
    String secondRejectBtn =
        "[data-testid='bank-request-reject-btn'][data-field-_id='" + secondId + "']";

    Path storageState =
        E2eSupport.authenticatedStorageState(browser, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/bank/requests");
        page.waitForLoadState();

        // Both pending requests are visible initially.
        assertThat(page.locator(firstRejectBtn))
            .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertThat(page.locator(secondRejectBtn)).hasCount(1);

        // A full reload would reset this marker; the in-place swap after the decision leaves it
        // set,
        // so it also proves the second open goes through the live path, not an accidental reload.
        page.evaluate("window.__krtNoReload = true;");
        dropFooter(page);

        // Decide the first request (a rejection books nothing and needs no holder).
        page.locator(firstRejectBtn).click(new Locator.ClickOptions().setTimeout(20_000));
        page.locator("[data-testid='bank-reject-reason']").fill("E2E first rejection");
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/bank/requests/" + firstId + "/reject")
                    && "POST".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> page.locator("[data-testid='bank-reject-submit']").click());

        // The decided request drops from the default pending-only queue in place; the second stays.
        assertThat(page.locator(firstRejectBtn))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(30_000));
        assertThat(page.locator(secondRejectBtn)).hasCount(1);

        // The regression assertion: clicking the second request's button must re-open the
        // shared reject modal. Before the fix the inline display:none left from closing it after
        // the
        // first decision outranked the class-based re-open, so it stayed hidden and nothing
        // happened.
        page.locator(secondRejectBtn).click(new Locator.ClickOptions().setTimeout(20_000));
        assertThat(page.locator("#bank-reject-request-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "deciding a request and opening the next must not reload the page");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-requests-second-decision");
        throw failure;
      }
    }
  }

  /**
   * Hides the {@code position:fixed} footer, which can intercept the trusted submit click on some
   * engines (the WebKit/Firefox footer-overlap flake the bank booking e2e also guards against).
   *
   * @param page the active page
   */
  private static void dropFooter(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer');"
            + " if (f) { f.style.display = 'none'; } }");
  }
}
