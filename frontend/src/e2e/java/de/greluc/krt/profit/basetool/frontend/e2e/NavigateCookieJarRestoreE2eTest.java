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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that {@link E2eSupport#restoreLostCookieJar} brings a signed-in context back after its
 * whole cookie jar was wiped, which is what WebKit does to a context when a navigation crashes.
 */
@Tag("e2e")
class NavigateCookieJarRestoreE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** A page only a signed-in member reaches; a signed-out request is redirected away from it. */
  private static final String MEMBER_PAGE = "/missions";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the configured browser. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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
   * Wipes the jar of a signed-in context and shows the member page is lost, then wipes it again,
   * restores the snapshot and shows the member page is reached with the session the server kept.
   */
  @Test
  void aRestoredCookieJarKeepsTheSession() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, USERNAME, PASSWORD);
        E2eSupport.navigate(page, baseUrl + MEMBER_PAGE);
        assertEquals(MEMBER_PAGE, path(page), "the signed-in context reaches the member page");
        List<Cookie> jar = context.cookies();
        assertTrue(!jar.isEmpty(), "the signed-in context holds cookies");

        context.clearCookies();
        E2eSupport.navigate(page, baseUrl + MEMBER_PAGE);
        assertNotEquals(MEMBER_PAGE, path(page), "a wiped jar must lose the session");

        context.clearCookies();
        E2eSupport.restoreLostCookieJar(context, jar, 1, baseUrl + MEMBER_PAGE);
        assertEquals(jar.size(), context.cookies().size(), "every cookie is put back");
        E2eSupport.navigate(page, baseUrl + MEMBER_PAGE);
        assertEquals(MEMBER_PAGE, path(page), "the restored jar must carry the session again");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "navigate-cookie-jar-restore");
        throw failure;
      }
    }
  }

  /**
   * Reads the path of the page's current URL.
   *
   * @param page the page
   * @return the URL path, without query or fragment
   */
  private static String path(Page page) {
    return URI.create(page.url()).getPath();
  }
}
