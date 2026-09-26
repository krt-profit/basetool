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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E smoke test: a real browser completes the Keycloak login against the frontend and saves a
 * reusable {@code storageState} snapshot.
 *
 * <p>Targets an ephemeral local stack, or the deployment named by {@code E2E_BASE_URL}.
 */
@Tag("e2e")
class LoginSmokeE2eTest {

  /**
   * Provisions the ephemeral stack (Postgres x2, Keycloak, Redis, backend, frontend) for the whole
   * test run via {@code docker compose}, or targets {@code E2E_BASE_URL} when set (staging). The
   * dev/test frontend serves HTTPS on 18081 (self-signed); the browser context sets {@code
   * ignoreHTTPSErrors} to accept the cert.
   */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** Keycloak username of the seeded synthetic test user. */
  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");

  /** Keycloak password of the seeded synthetic test user (throwaway, test realm only). */
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Time, in milliseconds, from the credential POST leaving the browser to the landing back on the
   * frontend; the submit's own retry budget is spent before this clock starts.
   */
  private static final double REDIRECT_TIMEOUT_MILLIS = 30_000;

  private static Playwright playwright;
  private static Browser browser;

  /** Boots a single headless browser (the configured {@code e2e.browser}) shared by every test. */
  @BeforeAll
  static void launchBrowser() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void closeBrowser() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Logs in through Keycloak, asserts a Spring Session cookie, and writes {@code
   * build/e2e/storageState.json}; a Playwright trace is saved to {@code build/e2e/trace.zip} and
   * failure diagnostics are captured on error.
   *
   * @throws Exception if a diagnostic artifact cannot be written
   */
  @Test
  void logsInThroughKeycloakAndSnapshotsStorageState() throws Exception {
    long startNanos = System.nanoTime();
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      context
          .tracing()
          .start(
              new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/oauth2/authorization/keycloak");

        int submitClicks = E2eSupport.submitKeycloakLogin(page, USERNAME, PASSWORD);
        long submittedNanos = System.nanoTime();

        page.waitForURL(
            url -> url.startsWith(baseUrl),
            new Page.WaitForURLOptions().setTimeout(REDIRECT_TIMEOUT_MILLIS));

        long landedNanos = System.nanoTime();
        long elapsedMillis = (landedNanos - startNanos) / 1_000_000L;
        long redirectMillis = (landedNanos - submittedNanos) / 1_000_000L;

        List<Cookie> cookies = context.cookies();
        boolean hasSessionCookie =
            cookies.stream().anyMatch(cookie -> "__Host-SESSION".equals(cookie.name));

        Path storageState = Path.of("build", "e2e", "storageState.json");
        Files.createDirectories(storageState.getParent());
        context.storageState(new BrowserContext.StorageStateOptions().setPath(storageState));

        System.out.printf(
            "[E2E] login OK in %d ms (credential POST to landing %d ms, submit clicks %d) |"
                + " landing=%s | __Host-SESSION cookie=%s | storageState=%s%n",
            elapsedMillis,
            redirectMillis,
            submitClicks,
            page.url(),
            hasSessionCookie,
            storageState.toAbsolutePath());

        assertTrue(
            page.url().startsWith(baseUrl),
            "after login the browser must be back on the frontend origin, was: " + page.url());
        assertTrue(
            hasSessionCookie, "a Spring Session cookie must be set after a successful OIDC login");
      } catch (RuntimeException | AssertionError failure) {
        captureFailureDiagnostics(page);
        throw failure;
      } finally {
        Path trace = Path.of("build", "e2e", "trace.zip");
        Files.createDirectories(trace.getParent());
        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
      }
    }
  }

  /**
   * Captures a screenshot, the page HTML and the current URL/title on failure; diagnostic errors
   * are swallowed so the original failure surfaces.
   *
   * @param page the Playwright page at the point of failure
   */
  private static void captureFailureDiagnostics(Page page) {
    try {
      Path dir = Path.of("build", "e2e");
      Files.createDirectories(dir);
      page.screenshot(
          new Page.ScreenshotOptions().setPath(dir.resolve("failure.png")).setFullPage(true));
      Files.writeString(dir.resolve("failure.html"), page.content());
      System.out.printf(
          "[E2E][FAIL] url=%s | title=%s | screenshot=%s%n",
          page.url(), page.title(), dir.resolve("failure.png").toAbsolutePath());
    } catch (RuntimeException | java.io.IOException diagnosticError) {
      System.out.println("[E2E][FAIL] diagnostics capture failed: " + diagnosticError);
    }
  }
}
