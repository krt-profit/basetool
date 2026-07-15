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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Role-permission flow: the action controls on {@code orders-detail.html} are each gated by a
 * different {@code sec:authorize} expression, so what a user sees on an order they CAN open is the
 * UI face of the edit/delete tenancy matrix. This test pins down three of them on the seeded
 * MATERIAL order:
 *
 * <ul>
 *   <li><b>Handover</b> ({@code order-handover-open}) — {@code hasAnyRole('LOGISTICIAN', 'OFFICER',
 *       'ADMIN')}: hidden from a plain KRT Member, shown to an Officer.
 *   <li><b>Edit</b> (the {@code edit-modal} trigger) — {@code hasRole('LOGISTICIAN')}: hidden from
 *       a Member, shown to an Officer (and Admin, via the role hierarchy).
 *   <li><b>Delete</b> (the {@code /delete} form) — {@code hasRole('ADMIN')}: hidden from both
 *       Member and Officer, shown only to an Admin.
 * </ul>
 *
 * <p>This is the first flow to exercise the {@code test-member}, {@code test-officer} and {@code
 * test-admin} realm users together and the multi-user login pattern — one fresh Keycloak session
 * per role, each in its own browser context (the suite's {@code authenticatedStorageState} writes a
 * single fixed path, so per-user isolation uses separate contexts logging in directly instead). It
 * relies on JUnit running test classes sequentially, so the IRIDIUM home assigned here is not
 * clobbered by a cross-Staffel class.
 */
@Tag("e2e")
class RolePermissionsE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String jobOrderId;

  /**
   * Launches the browser and, for the ephemeral stack, homes the admin/officer/member test users in
   * IRIDIUM and seeds a job order whose detail page both roles can open.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      // Materialise (first login syncs the app_user row) and home both non-admin roles in IRIDIUM,
      // so their session has an org context when they open the order detail page.
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD),
          IRIDIUM_ID,
          false,
          false);
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
          IRIDIUM_ID,
          false,
          false);
      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Role Material");
      jobOrderId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, IRIDIUM_ID, "E2E Role Order", materialId, 650, 100.0);
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

  /** A plain KRT Member opens the order but the role-gated handover control is not rendered. */
  @Test
  void squadronMemberDoesNotSeeTheHandoverControl() {
    assertHandoverControlVisibility(MEMBER_USER, MEMBER_PASSWORD, false);
  }

  /** An Officer opens the same order and sees the handover control. */
  @Test
  void officerSeesTheHandoverControl() {
    assertHandoverControlVisibility(OFFICER_USER, OFFICER_PASSWORD, true);
  }

  /** A plain KRT Member sees neither the (LOGISTICIAN) edit nor the (ADMIN) delete control. */
  @Test
  void squadronMemberSeesNoEditOrDeleteControls() {
    assertEditAndDeleteControls(MEMBER_USER, MEMBER_PASSWORD, false, false);
  }

  /** An Officer sees the LOGISTICIAN-gated edit control but not the ADMIN-gated delete control. */
  @Test
  void officerSeesEditButNotDeleteControl() {
    assertEditAndDeleteControls(OFFICER_USER, OFFICER_PASSWORD, true, false);
  }

  /**
   * An Admin sees both the edit control (via the role hierarchy) and the ADMIN-gated delete form.
   */
  @Test
  void adminSeesEditAndDeleteControls() {
    assertEditAndDeleteControls(ADMIN_USER, ADMIN_PASSWORD, true, true);
  }

  /**
   * Logs in as the given user in a fresh context, opens the seeded order, and asserts whether the
   * {@code order-handover-open} control is present. {@code nav-logout} is asserted first as a
   * locale-independent proof that the authenticated app shell actually rendered (so an absent
   * handover control means "role-gated away", not "page failed to load"). It is used instead of a
   * nav link because the links now sit inside collapsed {@code <details>} sections (hidden until
   * expanded), whereas the logout control is always rendered for a logged-in user.
   *
   * @param user the Keycloak username to log in as
   * @param password the Keycloak password
   * @param expectedVisible whether the handover control should be visible for this role
   */
  private void assertHandoverControlVisibility(
      String user, String password, boolean expectedVisible) {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, user, password);
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId + "?tab=handovers");
        page.waitForLoadState();
        assertThat(page.getByTestId("nav-logout")).isVisible();
        if (expectedVisible) {
          assertThat(page.getByTestId("order-handover-open")).isVisible();
        } else {
          assertThat(page.getByTestId("order-handover-open")).hasCount(0);
        }
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "role-handover-" + user);
        throw failure;
      }
    }
  }

  /**
   * Logs in as the given user in a fresh context, opens the seeded order, and asserts whether the
   * LOGISTICIAN-gated edit-modal trigger and the ADMIN-gated delete form are rendered. As with the
   * handover check, {@code nav-logout} is asserted first as locale-independent proof the
   * authenticated shell rendered, so an absent control means "role-gated away", not "page failed to
   * load". The delete control is matched by its {@code /delete} form action — the only such form on
   * the page — and asserted on its submit button so presence implies a clickable control.
   *
   * @param user the Keycloak username to log in as
   * @param password the Keycloak password
   * @param editExpected whether the edit-modal trigger should be visible for this role
   * @param deleteExpected whether the delete form should be visible for this role
   */
  private void assertEditAndDeleteControls(
      String user, String password, boolean editExpected, boolean deleteExpected) {
    String baseUrl = STACK.baseUrl();
    String editTrigger = "[data-trigger='open-modal-display'][data-modal-id='edit-modal']";
    String deleteButton = "form[action*='/delete'] button";
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, user, password);
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId);
        page.waitForLoadState();
        assertThat(page.getByTestId("nav-logout")).isVisible();

        if (editExpected) {
          assertThat(page.locator(editTrigger)).isVisible();
        } else {
          assertThat(page.locator(editTrigger)).hasCount(0);
        }
        if (deleteExpected) {
          assertThat(page.locator(deleteButton)).isVisible();
        } else {
          assertThat(page.locator(deleteButton)).hasCount(0);
        }
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "role-edit-delete-" + user);
        throw failure;
      }
    }
  }
}
