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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end visibility matrix for the org-unit bank account-responsibility feature
 * (REQ-BANK-034..038, ADR-0043), driven through the real Keycloak login + JWT-to-authority chain
 * that the unit tests ({@code OrgUnitBankAccessServiceTest}) can only mock.
 *
 * <p>The matrix is asserted race-free against the backend endpoints as each user (mirroring {@code
 * BankPermissionsE2eTest}), with one UI check for the Halter-redacted drill-in. It deliberately
 * exercises the two account types that are reliably seedable on the shared ephemeral stack — {@code
 * ORG_UNIT} (per-unit, idempotent) and {@code SPECIAL} (non-singleton) — across all four grantee
 * kinds (MEMBERSHIP_ROLE/GLOBAL_ROLE/USER/ALL_MEMBERS) plus the admin override. The
 * fixed-visibility global singletons {@code CARTEL} / {@code CARTEL_BANK} are awkward to seed on a
 * shared stack and carry no per-grant logic, so they stay covered by the unit suite.
 *
 * <p>The crown jewel here is the role-model assertion: a caller holding the {@code OFFICER}
 * Keycloak role but <em>no</em> Bereich/OL membership must NOT auto-see a Sonderkonto
 * (REQ-BANK-037) — the auto-view keys off membership, never the role — which only an end-to-end
 * test with a real token can prove.
 */
@Tag("e2e")
class OrgUnitBankVisibilityMatrixE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String BEREICH_USER = "test-bereich";
  private static final String BEREICH_PASSWORD = "test-bereich-pw";
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String NONVIEWER_USER = "test-none";
  private static final String NONVIEWER_PASSWORD = "test-none-pw";

  private static final String BALANCES = "/api/v1/org-units/bank/balances";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String specialAccountId;
  private static String orgUnitAccountId;
  private static String memberUserId;

  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    String bereichId =
        seeder.createBereich(ADMIN_USER, ADMIN_PASSWORD, "E2E Bank Vis Bereich", "EBVBR");
    seeder.addBereichLeader(
        ADMIN_USER,
        ADMIN_PASSWORD,
        bereichId,
        seeder.getUserId(BEREICH_USER, BEREICH_PASSWORD),
        "LEITER");

    String squadronId =
        seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Bank Vis Staffel", "EBVST");
    orgUnitAccountId =
        seeder.ensureOrgUnitBankAccount(
            MGMT_USER, MGMT_PASSWORD, "E2E Bank Vis Staffelkonto", squadronId);

    specialAccountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Bank Vis Sonderkonto", "SPECIAL");

    memberUserId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
    seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
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

  @Test
  void specialAutoViewIsMembershipBasedNotOfficerRole() {
    assertTrue(
        balancesContain(BEREICH_USER, BEREICH_PASSWORD, specialAccountId),
        "a Bereichsleiter auto-sees the Sonderkonto");
    assertFalse(
        balancesContain(OFFICER_USER, OFFICER_PASSWORD, specialAccountId),
        "an OFFICER without a Bereich/OL seat must NOT auto-see the Sonderkonto");
    assertFalse(
        balancesContain(MEMBER_USER, MEMBER_PASSWORD, specialAccountId),
        "a plain member must NOT auto-see the Sonderkonto");

    assertEquals(
        200,
        seeder.attemptGetStatus(
            BEREICH_USER, BEREICH_PASSWORD, "/api/v1/org-units/bank/accounts/" + specialAccountId),
        "a Bereichsleiter may open the Sonderkonto drill-in");
    assertEquals(
        403,
        seeder.attemptGetStatus(
            OFFICER_USER, OFFICER_PASSWORD, "/api/v1/org-units/bank/accounts/" + specialAccountId),
        "an OFFICER without a Bereich/OL seat is denied the Sonderkonto drill-in");
  }

  @Test
  void nonViewerDeniedOnDrillInEndpoints() {
    assertFalse(
        balancesContain(NONVIEWER_USER, NONVIEWER_PASSWORD, specialAccountId),
        "a non-viewer's balances must not contain the Sonderkonto");
    assertFalse(
        balancesContain(NONVIEWER_USER, NONVIEWER_PASSWORD, orgUnitAccountId),
        "a non-viewer's balances must not contain the Staffel account");
    assertEquals(
        403,
        seeder.attemptGetStatus(
            NONVIEWER_USER,
            NONVIEWER_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + specialAccountId),
        "a non-viewer is denied the Sonderkonto drill-in");
    assertEquals(
        403,
        seeder.attemptGetStatus(
            NONVIEWER_USER,
            NONVIEWER_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + orgUnitAccountId),
        "a non-viewer is denied the Staffel-account drill-in");
  }

  @Test
  void userGrantMakesOrgUnitAccountVisibleToThatUser() {
    assertFalse(
        balancesContain(MEMBER_USER, MEMBER_PASSWORD, orgUnitAccountId),
        "a plain member does not see the Staffel account before the grant");
    assertEquals(
        200,
        seeder.postForStatus(
            ADMIN_USER,
            ADMIN_PASSWORD,
            "/api/v1/org-units/bank/accounts/"
                + orgUnitAccountId
                + "/visibility/user/"
                + memberUserId,
            "{}"),
        "admin may grant an individual user view of the Staffel account");
    assertTrue(
        balancesContain(MEMBER_USER, MEMBER_PASSWORD, orgUnitAccountId),
        "the individually granted member now sees the Staffel account");
  }

  @Test
  void globalRoleAndAllMembersGrantsOnSpecial() {
    String special =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Bank Vis Grant SK", "SPECIAL");
    String detail = "/api/v1/org-units/bank/accounts/" + special;

    assertEquals(
        200,
        seeder.postForStatus(MGMT_USER, MGMT_PASSWORD, detail + "/visibility/role/OFFICER", "{}"),
        "bank management may grant the OFFICER global-role bucket on a Sonderkonto");
    assertTrue(
        balancesContain(OFFICER_USER, OFFICER_PASSWORD, special),
        "an OFFICER now sees the Sonderkonto via the global-role grant");
    assertFalse(
        balancesContain(MEMBER_USER, MEMBER_PASSWORD, special),
        "a plain member still does not see it (the grant was OFFICER, not all-members)");

    assertEquals(
        200,
        seeder.putForStatus(
            MGMT_USER, MGMT_PASSWORD, detail + "/visibility/all-members/true", "{}"),
        "bank management may grant the all-members bucket on a Sonderkonto");
    assertTrue(
        balancesContain(MEMBER_USER, MEMBER_PASSWORD, special),
        "any member now sees the Sonderkonto via the all-members grant");
  }

  @Test
  void targetAndVisibilityConfigAuthority() {
    long version = accountVersion(ADMIN_USER, ADMIN_PASSWORD, orgUnitAccountId);
    assertEquals(
        200,
        seeder.putForStatus(
            ADMIN_USER,
            ADMIN_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + orgUnitAccountId + "/balance-target",
            "{\"target\":10000000,\"version\":" + version + "}"),
        "admin may set a balance target (override)");
    assertEquals(
        403,
        seeder.putForStatus(
            MEMBER_USER,
            MEMBER_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + orgUnitAccountId + "/balance-target",
            "{\"target\":1,\"version\":0}"),
        "a plain member may not set a balance target");

    String special =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Bank Vis Config SK", "SPECIAL");
    assertEquals(
        403,
        seeder.postForStatus(
            BEREICH_USER,
            BEREICH_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + special + "/visibility/role/OFFICER",
            "{}"),
        "a Bereichsleiter may not configure a Sonderkonto's visibility");
    assertEquals(
        200,
        seeder.postForStatus(
            MGMT_USER,
            MGMT_PASSWORD,
            "/api/v1/org-units/bank/accounts/" + special + "/visibility/role/OFFICER",
            "{}"),
        "bank management may configure a Sonderkonto's visibility");
  }

  @Test
  void drillInDetailHistoryOmitsHalterColumn() {
    String special =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Bank Vis Drill SK", "SPECIAL");
    String holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, memberUserId);
    assertEquals(
        201,
        seeder.bankDeposit(MGMT_USER, MGMT_PASSWORD, special, holderId, 4242),
        "a booking is seeded so the drill-in history table renders");

    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, BEREICH_USER, BEREICH_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank/accounts/" + special);
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='org-unit-bank-bookings-panel']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-testid='org-unit-bank-bookings-panel'] thead th"))
            .hasCount(5);
        assertThat(
                page.locator(
                    "[data-testid='org-unit-bank-bookings-panel'] thead th",
                    new Page.LocatorOptions().setHasText("Halter")))
            .hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-unit-bank-visibility-drill-in");
        throw failure;
      }
    }
  }

  /**
   * Whether the org-unit bank balances of {@code username} contain the account with the given id.
   *
   * @param username the caller's Keycloak username
   * @param password the caller's password
   * @param accountId the bank account id to look for
   * @return {@code true} iff the caller's {@code /balances} response lists that account
   */
  private static boolean balancesContain(String username, String password, String accountId) {
    JsonArray balances =
        JsonParser.parseString(seeder.getBody(username, password, BALANCES)).getAsJsonArray();
    for (JsonElement element : balances) {
      if (accountId.equals(element.getAsJsonObject().get("accountId").getAsString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reads the optimistic-locking version of a bank account via the management detail endpoint, so a
   * balance-target write can echo it. {@code GET /api/v1/bank/accounts/{id}} returns a {@code
   * BankAccountDetailDto}, so the {@code version} lives on the nested {@code account} object.
   *
   * @param username a username that may read the account (admin / management)
   * @param password the password
   * @param accountId the account id
   * @return the account's current {@code @Version}
   */
  private static long accountVersion(String username, String password, String accountId) {
    return JsonParser.parseString(
            seeder.getBody(username, password, "/api/v1/bank/accounts/" + accountId))
        .getAsJsonObject()
        .getAsJsonObject("account")
        .get("version")
        .getAsLong();
  }
}
