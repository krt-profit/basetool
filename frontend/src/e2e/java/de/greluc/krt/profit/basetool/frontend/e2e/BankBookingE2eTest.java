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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Bank booking flows end to end (REQ-BANK-004/-006/-011, epic #556): deposit / withdrawal /
 * transfer happy paths plus the stable 409 rejections (holder overdraft, self-transfer), asserted
 * against the compute-on-read balances. Two consecutive deposits are additionally driven through
 * the real K1 modal to prove the AJAX → {@code /api/proxy/bank} → backend → in-place account-body
 * swap chain (#579, REQ-FE-001): the success path re-renders the money region without a reload, and
 * the immediate second deposit cannot 409 (money bookings carry no client {@code @Version}).
 */
@Tag("e2e")
class BankBookingE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String accountId;
  private static String holderAId;
  private static String holderBId;
  private static String uiAccountId;
  private static String uiHolderId;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    String mgmtId = seeder.getUserId(MGMT_USER, MGMT_PASSWORD);

    accountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Booking Account", "SPECIAL");
    holderAId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, employeeId);
    holderBId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, mgmtId);

    // A separate account the employee is granted on, for the UI deposit drive.
    uiAccountId = seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Booking UI", "SPECIAL");
    seeder.createBankGrant(MGMT_USER, MGMT_PASSWORD, employeeId, uiAccountId, true, true, true);
    uiHolderId = holderAId;
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
   * A deposit then a partial withdrawal move the compute-on-read balance the expected way. Uses a
   * dedicated fresh account so the absolute-balance assertions are independent of test order (JUnit
   * method order is not source order, and the shared stack persists postings).
   */
  @Test
  void depositThenWithdrawalUpdatesBalance() {
    String account = freshAccount("E2E Booking Deposit");
    assertEquals(201, seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, account, holderAId, 1000));
    assertEquals(
        0, balance(account).compareTo(new BigDecimal("1000")), "balance is 1000 after the deposit");
    // The fee is added on top (ADR-0052): a 400 payout debits the account the gross 402 (400 + 2
    // fee at 0.5%), leaving 1000 - 402 = 598.
    assertEquals(201, seeder.bankWithdraw(MGMT_USER, MGMT_PASSWORD, account, holderAId, 400));
    assertEquals(
        0,
        balance(account).compareTo(new BigDecimal("598")),
        "balance is 598 after the withdrawal (400 payout + 2 fee)");
  }

  /**
   * A withdrawal exceeding the holder's stash is rejected with the stable 409 — balance untouched.
   */
  @Test
  void holderOverdraftIsRejectedWith409() {
    String account = freshAccount("E2E Booking Overdraft");
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, account, holderBId, 300);
    BigDecimal before = balance(account);
    assertEquals(
        409,
        seeder.bankWithdraw(MGMT_USER, MGMT_PASSWORD, account, holderBId, 1000),
        "withdrawing more than the holder holds is a 409");
    assertEquals(0, balance(account).compareTo(before), "balance is unchanged after the rejection");
  }

  /**
   * A transfer between two accounts AND two different holders moves money and adds the in-game fee
   * on top (REQ-BANK-033, ADR-0052): the source is debited the gross (amount + fee) while the
   * destination is credited the full entered amount. With the seeded 0.5% rate, 200 aUEC carries a
   * 1 aUEC fee, so the source loses 201 and the destination gains the full 200.
   */
  @Test
  void transferBetweenAccountsMovesMoney() {
    String source = freshAccount("E2E Booking Source");
    String dest = freshAccount("E2E Booking Dest");
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, source, holderAId, 500);
    BigDecimal sourceBefore = balance(source);
    BigDecimal destBefore = balance(dest);
    String body =
        "{\"sourceAccountId\":\""
            + source
            + "\",\"sourceHolderId\":\""
            + holderAId
            + "\",\"destinationAccountId\":\""
            + dest
            + "\",\"destinationHolderId\":\""
            + holderBId
            // REQ-BANK-045: SPECIAL source account mandates a Begründung on a transfer.
            + "\",\"amount\":200,\"justification\":\"E2E transfer reason\"}";
    assertEquals(
        201,
        seeder.postForStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/transfers", body),
        "the transfer is accepted");
    assertEquals(
        0,
        balance(source).compareTo(sourceBefore.subtract(new BigDecimal("201"))),
        "the source lost the gross (200 + 1 aUEC in-game fee)");
    assertEquals(
        0,
        balance(dest).compareTo(destBefore.add(new BigDecimal("200"))),
        "the destination gained the full entered amount (200)");
  }

  /** A transfer to the same account AND the same holder is a no-op booking — rejected with 409. */
  @Test
  void selfTransferIsRejectedWith409() {
    String account = freshAccount("E2E Booking Self");
    seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, account, holderAId, 100);
    String body =
        "{\"sourceAccountId\":\""
            + account
            + "\",\"sourceHolderId\":\""
            + holderAId
            + "\",\"destinationAccountId\":\""
            + account
            + "\",\"destinationHolderId\":\""
            + holderAId
            + "\",\"amount\":50}";
    assertEquals(
        409,
        seeder.postForStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/transfers", body),
        "a same-account same-holder transfer is a 409");
  }

  /** A fractional amount is rejected at the validation layer with a 400. */
  @Test
  void fractionalAmountIsRejectedWith400() {
    String body =
        "{\"accountId\":\"" + accountId + "\",\"holderId\":\"" + holderAId + "\",\"amount\":10.5}";
    assertEquals(
        400,
        seeder.postForStatus(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/deposits", body),
        "a fractional aUEC amount is rejected");
  }

  /**
   * Creates a fresh SPECIAL account for one test method so absolute-balance assertions are
   * isolated.
   *
   * @param name the account display name (made unique per call)
   * @return the created account id
   */
  private static String freshAccount(String name) {
    return seeder.createBankAccount(
        MGMT_USER, MGMT_PASSWORD, name + " " + java.util.UUID.randomUUID(), "SPECIAL");
  }

  /**
   * Drives two consecutive deposits through the K1 modal as the granted employee and proves the
   * #579 in-place behaviour: the success path swaps the account body via {@code
   * fragment=accountBody} instead of reloading, so the {@code window.__krtNoReload} marker set
   * before the first deposit survives BOTH writes, the visible balance updates in place each time,
   * and the immediate second deposit (no reload between) is accepted — money bookings carry no
   * client {@code @Version}, so a stale-version 409 is structurally impossible.
   */
  @Test
  void uiDepositThroughModalUpdatesBalanceInPlace() {
    String baseUrl = STACK.baseUrl();
    BigDecimal before = balance(uiAccountId);
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, EMPLOYEE_USER, EMPLOYEE_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/bank/accounts/" + uiAccountId);
        page.waitForLoadState();
        // Marker + drop the position:fixed footer (it can intercept the trusted submit click on
        // WebKit). A full reload wipes the marker; an in-place swap leaves it intact.
        page.evaluate("window.__krtNoReload = true;");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        depositInPlace(page, "777");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the first deposit must not reload the page");
        depositInPlace(page, "777");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the immediate second deposit must not reload either (no stale-version 409)");
        // The facts strip is still present after the in-place swaps (never a full navigation).
        assertThat(page.locator("[data-testid='bank-balance']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-booking-ui-deposit");
        throw failure;
      }
    }
    assertEquals(
        0,
        balance(uiAccountId).compareTo(before.add(new BigDecimal("1554"))),
        "both in-place UI deposits increased the balance by 777 each");
  }

  /**
   * Opens the unified "Kontobewegung" modal (REQ-BANK-017, #997) — Einzahlung is the default type —
   * books the given amount to the UI holder and waits for the account-body swap to repaint the
   * balance in place, without any page navigation. Captures the pre-deposit balance text and waits
   * on the XHR POST plus the post-swap DOM change, so the assertion is independent of locale number
   * formatting.
   *
   * @param page the active page
   * @param amount the whole-aUEC amount to deposit
   */
  private static void depositInPlace(Page page, String amount) {
    String balanceBefore = page.locator("[data-testid='bank-balance']").textContent().trim();
    page.locator("[data-testid='bank-movement-open']")
        .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(20_000));
    // The movement type defaults to DEPOSIT (first offered type); the deposit fields are shown.
    page.locator("[data-testid='bank-movement-amount']").fill(amount);
    // The holder picker is a searchable combobox; pick the employee's holder by its value.
    E2eSupport.selectComboboxByValue(
        page.locator("[data-testid='bank-movement-deposit-holder']"), uiHolderId);
    page.waitForResponse(
        r -> r.url().contains("/api/proxy/bank/deposits") && "POST".equals(r.request().method()),
        // 60 s (above the 30 s default): the deposit's proxied XHR round-trip can outrun 30 s on a
        // contended CI runner (the Firefox-only flake window), timing out an otherwise-correct
        // POST.
        new Page.WaitForResponseOptions().setTimeout(60_000),
        () -> page.locator("[data-testid='bank-movement-submit']").click());
    // The accountBody swap repaints the facts strip; wait for the balance text to actually change
    // so
    // the proof is that the money region updated IN PLACE, not via a reload.
    page.waitForFunction(
        "old => { const el = document.querySelector('[data-testid=\"bank-balance\"]');"
            + " return el && el.textContent.trim() !== old; }",
        balanceBefore);
  }

  /**
   * Reads an account's compute-on-read balance from its detail JSON.
   *
   * @param account the account id
   * @return the current balance
   */
  private static BigDecimal balance(String account) {
    JsonObject detail =
        JsonParser.parseString(
                seeder.getBody(MGMT_USER, MGMT_PASSWORD, "/api/v1/bank/accounts/" + account))
            .getAsJsonObject();
    return detail.getAsJsonObject("account").get("balance").getAsBigDecimal();
  }
}
