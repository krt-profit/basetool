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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Org-unit officer/lead bank features end to end (epic #666, REQ-BANK-021/-022/-023). Proves the
 * full confirm-before-post lifecycle across two audiences on the real UI:
 *
 * <ul>
 *   <li><b>F1:</b> an officer who oversees a Staffel sees the balance-only card on the slim {@code
 *       /org-unit-bank} page; a plain member may open the same member-or-above page and sees its
 *       nav entry, but — overseeing and granted no account — no balance card (REQ-BANK-038).
 *   <li><b>F2:</b> the officer raises a deposit request through the modal (recorded {@code
 *       PENDING}, no money moved); a granted bank employee confirms it from the staff queue,
 *       recording a holder, which books the deposit onto the org-unit account; the requester can
 *       cancel a pending request, and the employee can reject one.
 *   <li><b>Role matrix:</b> the officer cannot reach the {@code BANK_EMPLOYEE}-only staff queue,
 *       and a pure bank employee cannot reach the officer/lead page.
 * </ul>
 *
 * <p><b>Drive via UI, verify via API.</b> Every mutation is driven through the real modal/form, but
 * the outcome (the request's status, the account balance) is read back from the backend so the
 * assertions never race the in-place AJAX swap. Each test uses a distinct amount so it can target
 * exactly the request it raised on the shared ephemeral stack.
 */
@Tag("e2e")
class BankOrgUnitRequestsE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";

  /** The canonical IRIDIUM Squadron seeded at stack bootstrap (the officer's overseen org unit). */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String accountId;
  private static String holderId;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    // The officer holds the Keycloak OFFICER role; making them a member of IRIDIUM gives them
    // oversight of IRIDIUM (currentOversightScope → their own Staffel).
    String officerId = seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
    seeder.assignStaffelMembership(
        ADMIN_USER, ADMIN_PASSWORD, officerId, IRIDIUM_SQUADRON_ID, false, false);

    // The ORG_UNIT account IRIDIUM owns (get-or-create: at most one per org unit).
    accountId =
        seeder.ensureOrgUnitBankAccount(
            MGMT_USER, MGMT_PASSWORD, "E2E Org-Unit Bank", IRIDIUM_SQUADRON_ID);

    // A registered holder the employee records on confirmation (a neutral player).
    String memberId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
    holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, memberId);

    // The employee may deposit/withdraw on the account (so they can confirm requests on it).
    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    seeder.createBankGrant(MGMT_USER, MGMT_PASSWORD, employeeId, accountId, true, true, false);
  }

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
   * F1: an officer sees their org unit's balance card on the slim page; a plain member reaches the
   * same member-or-above page and sees its nav entry, but — overseeing and granted no account — no
   * balance card. The account-responsibility feature opened the page to every member (REQ-BANK-038,
   * read-only drill-in); only the balance card itself stays scoped to oversight/grants.
   */
  @Test
  void officerSeesOrgUnitBalanceAndPlainMemberDoesNot() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        assertThat(page.locator("[data-testid='org-unit-bank-card']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-testid='nav-org-unit-bank']")).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-officer");
        throw failure;
      }
    }
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        page.waitForLoadState();
        // The page opened to every member (REQ-BANK-038), so the member reaches it and sees the
        // nav entry; overseeing and granted no account, they still see no balance card.
        assertThat(page.locator("[data-testid='nav-org-unit-bank']")).isVisible();
        assertThat(page.locator("[data-testid='org-unit-bank-card']")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-member");
        throw failure;
      }
    }
  }

  /**
   * F2 happy path: the officer raises a deposit request (off-ledger, PENDING) through the modal; a
   * granted bank employee confirms it from the staff queue, recording a holder, which books the
   * deposit onto the org-unit account. Verified via the backend: the request flips to CONFIRMED and
   * the account balance grows by the requested amount.
   */
  @Test
  void officerRequestConfirmedByBankEmployeeMovesTheBalance() {
    String baseUrl = STACK.baseUrl();
    long amount = 7001;
    long balanceBefore = seeder.bankAccountBalance(MGMT_USER, MGMT_PASSWORD, accountId);

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        createRequest(page, "DEPOSIT", Long.toString(amount));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-request-create");
        throw failure;
      }
    }

    String requestId =
        seeder.findOwnPendingBookingRequestId(OFFICER_USER, OFFICER_PASSWORD, accountId, amount);
    assertNotNull(requestId, "the officer's pending deposit request was recorded");

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank/requests");
        confirmRequest(page, requestId);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-request-confirm");
        throw failure;
      }
    }

    assertEquals(
        "CONFIRMED",
        seeder.bookingRequestStatus(OFFICER_USER, OFFICER_PASSWORD, requestId),
        "the employee's confirmation moved the request to CONFIRMED");
    assertEquals(
        balanceBefore + amount,
        seeder.bankAccountBalance(MGMT_USER, MGMT_PASSWORD, accountId),
        "the confirmation booked the deposit onto the org-unit account");
  }

  /** F2: the officer can withdraw (cancel) their own pending request from the slim page. */
  @Test
  void officerCancelsOwnPendingRequest() {
    String baseUrl = STACK.baseUrl();
    long amount = 7002;

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        createRequest(page, "WITHDRAWAL", Long.toString(amount));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-cancel-create");
        throw failure;
      }
    }

    String requestId =
        seeder.findOwnPendingBookingRequestId(OFFICER_USER, OFFICER_PASSWORD, accountId, amount);
    assertNotNull(requestId, "the officer's pending withdrawal request was recorded");

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        // The own-requests list now lives behind the "Meine Anträge" tab; activate it so the cancel
        // control inside the (otherwise hidden) panel becomes clickable.
        page.locator("[data-testid='org-unit-bank-tab-requests']")
            .click(new Locator.ClickOptions().setTimeout(20_000));
        Locator cancelButton =
            page.locator("form:has(input[name='_id'][value='" + requestId + "'])")
                .locator("[data-testid='org-unit-bank-cancel-btn']");
        dropFooter(page);
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/org-units/bank/requests/" + requestId + "/cancel")
                    && "POST".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            cancelButton::click);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-cancel");
        throw failure;
      }
    }

    assertEquals(
        "CANCELLED",
        seeder.bookingRequestStatus(OFFICER_USER, OFFICER_PASSWORD, requestId),
        "cancelling withdrew the pending request");
  }

  /** F2: a bank employee rejects a pending request with a reason — no money moves. */
  @Test
  void bankEmployeeRejectsPendingRequest() {
    String baseUrl = STACK.baseUrl();
    long amount = 7003;
    long balanceBefore = seeder.bankAccountBalance(MGMT_USER, MGMT_PASSWORD, accountId);

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        createRequest(page, "DEPOSIT", Long.toString(amount));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-reject-create");
        throw failure;
      }
    }

    String requestId =
        seeder.findOwnPendingBookingRequestId(OFFICER_USER, OFFICER_PASSWORD, accountId, amount);
    assertNotNull(requestId, "the officer's pending deposit request was recorded");

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank/requests");
        page.locator("[data-testid='bank-request-reject-btn'][data-field-_id='" + requestId + "']")
            .click(new Locator.ClickOptions().setTimeout(20_000));
        page.locator("[data-testid='bank-reject-reason']").fill("E2E rejection");
        dropFooter(page);
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/bank/requests/" + requestId + "/reject")
                    && "POST".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> page.locator("[data-testid='bank-reject-submit']").click());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-reject");
        throw failure;
      }
    }

    assertEquals(
        "REJECTED",
        seeder.bookingRequestStatus(OFFICER_USER, OFFICER_PASSWORD, requestId),
        "the employee rejected the request");
    assertEquals(
        balanceBefore,
        seeder.bankAccountBalance(MGMT_USER, MGMT_PASSWORD, accountId),
        "a rejection moves no money");
  }

  /**
   * Role matrix: the officer cannot reach the {@code BANK_EMPLOYEE}-only staff queue, and a pure
   * bank employee (no officer/lead role) cannot reach the officer/lead org-unit page.
   */
  @Test
  void roleBoundariesAreEnforced() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank/requests");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='bank-requests-table']")).hasCount(0);
        assertThat(page.locator("[data-testid='bank-request-row']")).hasCount(0);
        assertThat(page.locator("[data-testid='nav-bank-requests']")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-officer-queue-forbidden");
        throw failure;
      }
    }
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='org-unit-bank-card']")).hasCount(0);
        assertThat(page.locator("[data-testid='nav-org-unit-bank']")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-employee-page-forbidden");
        throw failure;
      }
    }
  }

  /**
   * REQ-BANK-055/-056 on the real UI: the officer raises a withdrawal request whose Empfaenger
   * picker is pre-filled with themselves, expands the row to read back what they filed, then
   * corrects the amount through the per-row edit modal. Verified via the backend so the assertions
   * never race the in-place AJAX swap.
   */
  @Test
  void officerEditsOwnPendingRequestAndTheAmountChanges() {
    String baseUrl = STACK.baseUrl();
    long amount = 7011;
    long corrected = 7012;

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        page.locator("[data-testid='org-unit-bank-request-btn']")
            .first()
            .click(new Locator.ClickOptions().setTimeout(20_000));
        page.locator("[data-testid='org-unit-request-type']").selectOption("WITHDRAWAL");
        page.locator("[data-testid='org-unit-request-account']").selectOption(accountId);
        page.locator("[data-testid='org-unit-request-amount']").fill(Long.toString(amount));
        // REQ-BANK-055: the Empfaenger picker is seeded server-side with the requester, so the
        // common case needs no interaction at all. A blank box here would mean the seed regressed.
        assertThat(page.locator("[data-testid='org-unit-request-cp-user']"))
            .not()
            .hasValue("", new LocatorAssertions.HasValueOptions().setTimeout(10_000));
        dropFooter(page);
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/org-units/bank/requests")
                    && "POST".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> page.locator("[data-testid='org-unit-request-submit']").click());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-edit-create");
        throw failure;
      }
    }

    String requestId =
        seeder.findOwnPendingBookingRequestId(OFFICER_USER, OFFICER_PASSWORD, accountId, amount);
    assertNotNull(requestId, "the officer's pending withdrawal request was recorded");

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank");
        page.locator("[data-testid='org-unit-bank-tab-requests']")
            .click(new Locator.ClickOptions().setTimeout(20_000));
        // The edit button and its modal live on the row carrying this request's id.
        Locator editButton =
            page.locator("#ou-req-edit-" + requestId)
                .locator("xpath=preceding::button[@data-testid='org-unit-bank-edit-btn'][1]");
        dropFooter(page);
        editButton.click(new Locator.ClickOptions().setTimeout(20_000));
        Locator modal = page.locator("#ou-req-edit-" + requestId);
        modal.locator("[data-testid='org-unit-bank-edit-amount']").fill(Long.toString(corrected));
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/org-units/bank/requests/" + requestId)
                    && "PUT".equals(r.request().method()),
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> modal.locator("[data-testid='org-unit-bank-edit-submit']").click());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-edit-apply");
        throw failure;
      }
    }

    assertEquals(
        "PENDING",
        seeder.bookingRequestStatus(OFFICER_USER, OFFICER_PASSWORD, requestId),
        "editing leaves the request pending");
    assertNotNull(
        seeder.findOwnPendingBookingRequestId(OFFICER_USER, OFFICER_PASSWORD, accountId, corrected),
        "the corrected amount was persisted");
  }

  /**
   * Raises a booking request through the slim page's modal: opens it (which primes the org unit),
   * picks the type, fills the amount and submits, waiting for the proxy POST to settle.
   *
   * @param page the active, logged-in officer page already on {@code /org-unit-bank}
   * @param type {@code DEPOSIT} or {@code WITHDRAWAL}
   * @param amount the whole-aUEC amount as a string
   */
  private static void createRequest(Page page, String type, String amount) {
    page.locator("[data-testid='org-unit-bank-request-btn']")
        .first()
        .click(new Locator.ClickOptions().setTimeout(20_000));
    page.locator("[data-testid='org-unit-request-type']").selectOption(type);
    // The deposit picker lists EVERY active account (REQ-BANK-042) and the stack may hold several,
    // so pin the IRIDIUM org-unit account explicitly rather than relying on the first option (the
    // selection is made after the type so the type-driven option filter does not reset it). For a
    // withdrawal it is the debitable account the officer oversees.
    page.locator("[data-testid='org-unit-request-account']").selectOption(accountId);
    page.locator("[data-testid='org-unit-request-amount']").fill(amount);
    dropFooter(page);
    page.waitForResponse(
        r ->
            r.url().contains("/api/proxy/org-units/bank/requests")
                && "POST".equals(r.request().method()),
        new Page.WaitForResponseOptions().setTimeout(60_000),
        () -> page.locator("[data-testid='org-unit-request-submit']").click());
  }

  /**
   * Confirms one specific pending request from the staff queue: clicks that request's confirm
   * button (targeted by its id), records the seeded holder and submits, waiting for the proxy POST.
   *
   * @param page the active, logged-in bank-employee page already on {@code /bank/requests}
   * @param requestId the id of the request to confirm
   */
  private static void confirmRequest(Page page, String requestId) {
    page.locator("[data-testid='bank-request-confirm-btn'][data-field-_id='" + requestId + "']")
        .click(new Locator.ClickOptions().setTimeout(20_000));
    E2eSupport.selectComboboxByValue(page.locator("[data-testid='bank-confirm-holder']"), holderId);
    dropFooter(page);
    page.waitForResponse(
        r ->
            r.url().contains("/api/proxy/bank/requests/" + requestId + "/confirm")
                && "POST".equals(r.request().method()),
        new Page.WaitForResponseOptions().setTimeout(60_000),
        () -> page.locator("[data-testid='bank-confirm-submit']").click());
  }

  /**
   * Hides the {@code position:fixed} footer, which can intercept the trusted submit click on some
   * engines (the WebKit/Firefox footer-overlap flake the booking e2e also guards against).
   *
   * @param page the active page
   */
  private static void dropFooter(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer');"
            + " if (f) { f.style.display = 'none'; } }");
  }
}
