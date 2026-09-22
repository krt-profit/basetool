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
 * End-to-end smoke test (see {@code docs/e2e-test/README.md}): a real Chromium browser completes
 * the Keycloak OIDC authorization-code login against the running frontend, lands back on an
 * authenticated frontend page, and yields a reusable {@code storageState} snapshot.
 *
 * <p>Target-agnostic: the {@link E2eStackExtension} registered below either provisions an ephemeral
 * local stack (default) or, when {@code E2E_BASE_URL} is set, targets that deployment (e.g.
 * staging). {@code STACK.baseUrl()} is the single source of truth for the origin under test.
 *
 * <p>Tagged {@code e2e} so it is picked up only by the dedicated {@code :frontend:e2eTest} Gradle
 * task and never by the regular {@code test}/{@code check} build.
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
   * Initiates the Spring {@code oauth2Login} flow, fills the Keycloak default-theme login form,
   * waits to be redirected back to the frontend origin, asserts a Spring Session cookie was
   * established, and writes the authenticated {@code storageState} to {@code
   * build/e2e/storageState.json} for later reuse. A Playwright trace (screenshots + DOM snapshots +
   * sources) is recorded for the whole run and saved to {@code build/e2e/trace.zip} — inspect it
   * with {@code npx playwright show-trace build/e2e/trace.zip}. On failure a screenshot + page HTML
   * dump is captured in addition.
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
        // Hitting the Spring authorization endpoint directly starts the OIDC code flow and
        // redirects the browser to the Keycloak login page for the `keycloak` client registration.
        E2eSupport.navigate(page, baseUrl + "/oauth2/authorization/keycloak");

        // Keycloak default login theme: stable element ids #username / #password / #kc-login.
        page.waitForSelector("#username");
        page.fill("#username", USERNAME);
        page.fill("#password", PASSWORD);
        page.click("#kc-login");

        // After a successful login Keycloak redirects to /login/oauth2/code/keycloak and Spring
        // establishes the session, then redirects somewhere on the frontend origin.
        page.waitForURL(
            url -> url.startsWith(baseUrl), new Page.WaitForURLOptions().setTimeout(30_000));

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        List<Cookie> cookies = context.cookies();
        boolean hasSessionCookie =
            // `__Host-` prefixed since FE-SEC-06: the browser accepts it only as Secure, Path=/ and
            // host-only, so its mere presence proves those three attributes held.
            cookies.stream().anyMatch(cookie -> "__Host-SESSION".equals(cookie.name));

        Path storageState = Path.of("build", "e2e", "storageState.json");
        Files.createDirectories(storageState.getParent());
        context.storageState(new BrowserContext.StorageStateOptions().setPath(storageState));

        System.out.printf(
            "[E2E] login OK in %d ms | landing=%s | __Host-SESSION cookie=%s | storageState=%s%n",
            elapsedMillis, page.url(), hasSessionCookie, storageState.toAbsolutePath());

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
   * Captures a screenshot, the page HTML and the current URL/title when a step fails, so a CI run
   * (or this Phase-0 spike) can see what the browser was actually showing. Best-effort: diagnostic
   * failures are swallowed so the original assertion/timeout error still surfaces.
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
