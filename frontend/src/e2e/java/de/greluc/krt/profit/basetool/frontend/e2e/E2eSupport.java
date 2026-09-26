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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for the E2E suite: launching the configured browser (Chromium / Firefox / WebKit),
 * driving the Keycloak login form, and reusing one authenticated session across the functional flow
 * tests.
 */
final class E2eSupport {

  /** Number of attempts for the Keycloak login flow before a timeout propagates. */
  private static final int LOGIN_MAX_ATTEMPTS = 3;

  /** Number of clicks {@link #submitKeycloakLogin} spends on getting the credential POST out. */
  private static final int KEYCLOAK_SUBMIT_ATTEMPTS = 2;

  /** Time, in milliseconds, one submit click has to put the credential POST on the wire. */
  private static final double KEYCLOAK_SUBMIT_TIMEOUT_MILLIS = 10_000;

  /** Path fragment of the Keycloak login form's action URL. */
  private static final String KEYCLOAK_AUTHENTICATE_PATH = "/login-actions/authenticate";

  /** Number of attempts for {@link #navigate}; the last attempt runs uncaught. */
  private static final int NAVIGATE_MAX_ATTEMPTS = 3;

  /**
   * Settle pause, in milliseconds, between a transient navigation abort and the retry. It gives the
   * reset HTTP/2 stream time to tear down so the re-issued GET opens a fresh one. Kept short
   * because the WebKit {@code INTERNAL_ERROR} reset clears almost immediately, and it only ever
   * runs on the abort path.
   */
  private static final int NAVIGATE_RETRY_BACKOFF_MILLIS = 500;

  /** Per-attempt navigation timeout of {@link #navigate}, in milliseconds. */
  private static final double NAVIGATE_TIMEOUT_MILLIS = 45_000;

  /**
   * Lowercase message fragments that mark a {@code page.navigate(...)} failure as a transient
   * connection abort in Chromium, Firefox, WebKit or Playwright itself.
   */
  private static final List<String> NAVIGATION_ABORT_SIGNATURES =
      List.of(
          "internal_error",
          "frameabortednavigation",
          "ns_binding_aborted",
          "ns_error_abort",
          "err_aborted",
          "err_http2_protocol_error",
          "interrupted");

  private E2eSupport() {}

  /**
   * Launches a headless browser of the engine named by the {@code e2e.browser} system property
   * ({@code chromium}, {@code firefox} or {@code webkit}).
   *
   * <p>When {@code managesStack} is set, {@code host.docker.internal} is mapped to the loopback;
   * WebKit relies on the OS hosts file for that.
   *
   * @param playwright the Playwright entry point
   * @param managesStack whether an ephemeral local stack is in play (enables the issuer-host remap)
   * @return a launched headless browser of the configured engine
   */
  static Browser launchBrowser(Playwright playwright, boolean managesStack) {
    String engine = System.getProperty("e2e.browser", "chromium").toLowerCase(Locale.ROOT);
    return switch (engine) {
      case "chromium" -> launchChromium(playwright, managesStack);
      case "firefox" -> launchFirefox(playwright, managesStack);
      case "webkit" -> launchWebkit(playwright, managesStack);
      default ->
          throw new IllegalArgumentException(
              "Unknown e2e.browser '" + engine + "' (expected chromium, firefox or webkit)");
    };
  }

  /**
   * Launches headless Chromium, remapping {@code host.docker.internal} to the loopback via {@code
   * --host-resolver-rules} for the ephemeral stack (override with {@code -De2e.hostResolverRules}).
   *
   * @param playwright the Playwright entry point
   * @param managesStack whether the ephemeral stack is in play (enables the remap)
   * @return a launched headless Chromium browser
   */
  private static Browser launchChromium(Playwright playwright, boolean managesStack) {
    BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
    String resolverRules =
        System.getProperty(
            "e2e.hostResolverRules", managesStack ? "MAP host.docker.internal 127.0.0.1" : "");
    if (!resolverRules.isBlank()) {
      options.setArgs(List.of("--host-resolver-rules=" + resolverRules));
    }
    return playwright.chromium().launch(options);
  }

  /**
   * Launches headless Firefox with HTTP/2, keep-alive and the insecure-login field warning
   * disabled, and for the ephemeral stack maps {@code host.docker.internal} to the loopback.
   *
   * @param playwright the Playwright entry point
   * @param managesStack whether the ephemeral stack is in play (enables the local-domain remap)
   * @return a launched headless Firefox browser
   */
  private static Browser launchFirefox(Playwright playwright, boolean managesStack) {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put("network.http.http2.enabled", false);
    prefs.put("network.http.keep-alive", false);
    prefs.put("security.insecure_field_warning.contextual.enabled", false);
    if (managesStack) {
      prefs.put("network.dns.localDomains", "host.docker.internal");
    }
    BrowserType.LaunchOptions options =
        new BrowserType.LaunchOptions().setHeadless(true).setFirefoxUserPrefs(prefs);
    return playwright.firefox().launch(options);
  }

  /**
   * Launches headless WebKit; for the ephemeral stack it first verifies that the OS hosts file maps
   * {@code host.docker.internal} to the loopback.
   *
   * @param playwright the Playwright entry point
   * @param managesStack whether the ephemeral stack is in play (requires the hosts-file mapping)
   * @return a launched headless WebKit browser
   */
  private static Browser launchWebkit(Playwright playwright, boolean managesStack) {
    if (managesStack) {
      requireHostDockerInternalOnLoopback();
    }
    return playwright.webkit().launch(new BrowserType.LaunchOptions().setHeadless(true));
  }

  /**
   * Verifies that {@code host.docker.internal} resolves to the loopback.
   *
   * @throws IllegalStateException if the host does not resolve, or resolves to a non-loopback
   *     address
   */
  private static void requireHostDockerInternalOnLoopback() {
    String fix =
        "Add '127.0.0.1 host.docker.internal' to your OS hosts file, or run WebKit against an"
            + " external deployment via E2E_BASE_URL. See docs/e2e-test/README.md.";
    try {
      InetAddress address = InetAddress.getByName("host.docker.internal");
      if (!address.isLoopbackAddress()) {
        throw new IllegalStateException(
            "WebKit cannot reach the ephemeral stack: 'host.docker.internal' resolves to "
                + address.getHostAddress()
                + ", not the loopback. Unlike Chromium/Firefox, WebKit has no launch-level DNS"
                + " override. "
                + fix);
      }
    } catch (UnknownHostException e) {
      throw new IllegalStateException(
          "WebKit cannot reach the ephemeral stack: 'host.docker.internal' does not resolve. "
              + fix,
          e);
    }
  }

  /**
   * Logs in through the Keycloak form and waits for the redirect back, retrying the whole flow up
   * to {@link #LOGIN_MAX_ATTEMPTS} times on timeout.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param username Keycloak username
   * @param password Keycloak password
   * @throws TimeoutError if the login does not complete within {@link #LOGIN_MAX_ATTEMPTS} attempts
   */
  static void login(Page page, String baseUrl, String username, String password) {
    for (int attempt = 1; attempt < LOGIN_MAX_ATTEMPTS; attempt++) {
      try {
        attemptLogin(page, baseUrl, username, password);
        return;
      } catch (TimeoutError timeout) {
        System.out.printf(
            "[E2E][login] attempt %d/%d did not reach %s in time; retrying with a fresh"
                + " navigation%n",
            attempt, LOGIN_MAX_ATTEMPTS, baseUrl);
      }
    }
    attemptLogin(page, baseUrl, username, password);
  }

  /**
   * Performs one login attempt: opens the authorization endpoint, submits the credentials and waits
   * up to 30 s for the redirect back.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param username Keycloak username
   * @param password Keycloak password
   * @throws TimeoutError if the form never appears, no credential POST leaves ({@link
   *     #submitKeycloakLogin}), or the redirect back to {@code baseUrl} does not arrive within 30 s
   */
  private static void attemptLogin(Page page, String baseUrl, String username, String password) {
    page.navigate(baseUrl + "/oauth2/authorization/keycloak");
    submitKeycloakLogin(page, username, password);
    page.waitForURL(
        url -> url.startsWith(baseUrl), new Page.WaitForURLOptions().setTimeout(30_000));
    acceptTermsIfPrompted(page, baseUrl);
  }

  /**
   * Fills and submits the Keycloak login form, and returns once the credential POST to {@code
   * login-actions/authenticate} has left the browser.
   *
   * <p>The form page must reach its {@code load} state and show {@code #kc-login} first. A click
   * that sends no POST within {@link #KEYCLOAK_SUBMIT_TIMEOUT_MILLIS} is repeated, up to {@link
   * #KEYCLOAK_SUBMIT_ATTEMPTS} clicks in all, refilling any field that no longer holds its value.
   *
   * @param page the page showing the Keycloak login form, or navigating to it
   * @param username Keycloak username
   * @param password Keycloak password
   * @return the number of clicks it took until the POST left, at least 1
   * @throws TimeoutError if the form does not render, or no POST leaves after {@link
   *     #KEYCLOAK_SUBMIT_ATTEMPTS} clicks
   */
  static int submitKeycloakLogin(Page page, String username, String password) {
    page.waitForLoadState(LoadState.LOAD);
    Locator submit = page.locator("#kc-login");
    submit.waitFor();
    for (int attempt = 1; ; attempt++) {
      fillUnlessHeld(page.locator("#username"), username);
      fillUnlessHeld(page.locator("#password"), password);
      try {
        page.waitForRequest(
            E2eSupport::isKeycloakCredentialPost,
            new Page.WaitForRequestOptions().setTimeout(KEYCLOAK_SUBMIT_TIMEOUT_MILLIS),
            submit::click);
        return attempt;
      } catch (TimeoutError noPost) {
        if (attempt >= KEYCLOAK_SUBMIT_ATTEMPTS) {
          throw noPost;
        }
        System.out.printf(
            "[E2E][login] submit click %d/%d sent no POST to %s within %.0f ms; clicking again%n",
            attempt,
            KEYCLOAK_SUBMIT_ATTEMPTS,
            KEYCLOAK_AUTHENTICATE_PATH,
            KEYCLOAK_SUBMIT_TIMEOUT_MILLIS);
      }
    }
  }

  /**
   * Fills {@code field} with {@code value} unless it already holds exactly that value, so a retry
   * does not refocus a field whose content survived.
   *
   * @param field the text or password input
   * @param value the value it must hold
   */
  private static void fillUnlessHeld(Locator field, String value) {
    if (!value.equals(field.inputValue())) {
      field.fill(value);
    }
  }

  /**
   * Reports whether {@code request} is the Keycloak login form's credential POST.
   *
   * @param request a request the page issued
   * @return {@code true} for a POST whose URL contains {@link #KEYCLOAK_AUTHENTICATE_PATH}
   */
  private static boolean isKeycloakCredentialPost(Request request) {
    return "POST".equals(request.method()) && request.url().contains(KEYCLOAK_AUTHENTICATE_PATH);
  }

  /**
   * Accepts the Terms-of-Use consent page if it appears after login (REQ-SEC-028); otherwise a
   * no-op.
   *
   * @param page the page that just completed the OIDC redirect
   * @param baseUrl the frontend origin
   */
  private static void acceptTermsIfPrompted(Page page, String baseUrl) {
    page.waitForLoadState();
    if (!page.url().startsWith(baseUrl + "/terms/accept")) {
      return;
    }
    System.out.println("[E2E][login] consent gate encountered; accepting the Terms of Use");
    page.click("#terms-accept-submit");
    page.waitForURL(
        url -> url.startsWith(baseUrl) && !url.contains("/terms/accept"),
        new Page.WaitForURLOptions().setTimeout(30_000));
  }

  /**
   * Logs in freshly and saves the session as a Playwright storageState file.
   *
   * @param browser the test class's browser
   * @param baseUrl the frontend origin
   * @param username Keycloak username
   * @param password Keycloak password
   * @return path to the storageState JSON for {@code newContext(...setStorageStatePath(...))}
   */
  static Path authenticatedStorageState(
      Browser browser, String baseUrl, String username, String password) {
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      login(page, baseUrl, username, password);
      Path path = Path.of("build", "e2e", "auth-state.json");
      Files.createDirectories(path.getParent());
      context.storageState(new BrowserContext.StorageStateOptions().setPath(path));
      return path;
    } catch (IOException e) {
      throw new IllegalStateException("Could not persist authenticated storageState", e);
    }
  }

  /**
   * Clicks a submit control that the fixed {@code .krt-footer} may cover, by hiding the footer
   * first, and waits for the resulting form post via {@link #awaitFormPost}.
   *
   * @param submit the submit control (a {@code <button type="submit">}) to click
   */
  static void clickSubmitClearingFooter(Locator submit) {
    Page page = submit.page();
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    awaitFormPost(page, submit::click);
  }

  /**
   * Selects the option with the given value in a searchable combobox (REQ-FE-011), looking up the
   * option only within this combobox's wrapper.
   *
   * @param comboInput the visible combobox textbox the enhancer rendered
   * @param value the option value to select
   */
  static void selectComboboxByValue(Locator comboInput, String value) {
    comboInput.click();
    comboInput
        .locator("xpath=..")
        .locator("li[role='option'][data-value='" + value + "']")
        .first()
        .click();
  }

  /**
   * Like {@link #selectComboboxByValue(Locator, String)}, but first types {@code searchText} to
   * narrow a server-side search picker (REQ-FE-016).
   *
   * @param comboInput the visible combobox textbox the enhancer rendered
   * @param value the option value to pick (matched via {@code data-value})
   * @param searchText the query to type first
   */
  static void selectComboboxByValue(Locator comboInput, String value, String searchText) {
    comboInput.click();
    comboInput.fill(searchText);
    comboInput
        .locator("xpath=..")
        .locator("li[role='option'][data-value='" + value + "']")
        .first()
        .click();
  }

  /**
   * Selects the first option with a non-empty value in a searchable combobox.
   *
   * @param comboInput the visible combobox textbox the enhancer rendered
   */
  static void selectComboboxFirstOption(Locator comboInput) {
    comboInput.click();
    comboInput
        .locator("xpath=..")
        .locator("li[role='option']:not([data-value=''])")
        .first()
        .click();
  }

  /**
   * Clears an optional searchable combobox by picking its empty-value option (REQ-FE-011).
   *
   * @param comboInput the visible combobox textbox the enhancer rendered
   */
  static void clearCombobox(Locator comboInput) {
    comboInput.click();
    comboInput.locator("xpath=..").locator("li[role='option'][data-value='']").first().click();
  }

  /**
   * Runs a submit action that triggers a full-page form POST and waits until the resulting document
   * (the redirect target, or a non-redirect POST response) has loaded.
   *
   * @param page the page whose main-frame post-submit navigation to await
   * @param submitAction the action (typically a submit-button click) that starts the form POST
   */
  static void awaitFormPost(Page page, Runnable submitAction) {
    page.waitForResponse(
        response -> {
          Request request = response.request();
          if (!request.isNavigationRequest() || !"document".equals(request.resourceType())) {
            return false;
          }
          if ("GET".equals(request.method())) {
            return true;
          }
          int status = response.status();
          return "POST".equals(request.method()) && (status < 300 || status >= 400);
        },
        new Page.WaitForResponseOptions().setTimeout(15_000),
        submitAction);
  }

  /**
   * Navigates to {@code url}, retrying up to {@link #NAVIGATE_MAX_ATTEMPTS} times on a transient
   * abort ({@link #isTransientNavigationAbort}) or a timeout.
   *
   * @param page the page to navigate
   * @param url the absolute URL to load
   * @return the main-frame {@link Response} of the successful navigation
   * @throws PlaywrightException if every attempt is aborted or times out, or on the first
   *     non-transient navigation failure
   */
  static Response navigate(Page page, String url) {
    Page.NavigateOptions options = new Page.NavigateOptions().setTimeout(NAVIGATE_TIMEOUT_MILLIS);
    for (int attempt = 1; attempt < NAVIGATE_MAX_ATTEMPTS; attempt++) {
      try {
        return page.navigate(url, options);
      } catch (TimeoutError timeout) {
        System.out.printf(
            "[E2E][navigate] attempt %d/%d to %s timed out after %.0f ms; settling then retrying%n",
            attempt, NAVIGATE_MAX_ATTEMPTS, url, NAVIGATE_TIMEOUT_MILLIS);
      } catch (PlaywrightException abort) {
        if (!isTransientNavigationAbort(abort)) {
          throw abort;
        }
        System.out.printf(
            "[E2E][navigate] attempt %d/%d to %s aborted (%s); settling then retrying%n",
            attempt,
            NAVIGATE_MAX_ATTEMPTS,
            url,
            String.valueOf(abort.getMessage()).lines().findFirst().orElse("navigation aborted"));
      }
      page.waitForTimeout(NAVIGATE_RETRY_BACKOFF_MILLIS);
      try {
        page.waitForLoadState(
            null, new Page.WaitForLoadStateOptions().setTimeout(NAVIGATE_TIMEOUT_MILLIS));
      } catch (TimeoutError settleTimeout) {
        System.out.printf(
            "[E2E][navigate] settle after attempt %d/%d to %s did not reach load state in time;"
                + " retrying anyway%n",
            attempt, NAVIGATE_MAX_ATTEMPTS, url);
      }
    }
    return page.navigate(url, options);
  }

  /**
   * Reports whether a navigation failure carries one of the {@link #NAVIGATION_ABORT_SIGNATURES}.
   *
   * @param error the exception thrown by {@code page.navigate(...)}
   * @return {@code true} if the message carries a known transient-abort signature; {@code false}
   *     otherwise, including when the message is {@code null}
   */
  private static boolean isTransientNavigationAbort(PlaywrightException error) {
    String message = error.getMessage();
    if (message == null) {
      return false;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    return NAVIGATION_ABORT_SIGNATURES.stream().anyMatch(lower::contains);
  }

  /**
   * Best-effort failure diagnostics: writes a full-page screenshot and the page HTML under {@code
   * build/e2e/<label>-failure.*} and prints the current URL, so a CI run can see what the browser
   * was showing when a flow failed.
   *
   * @param page the page at the point of failure
   * @param label short prefix for the artifact filenames
   */
  static void dump(Page page, String label) {
    try {
      Path dir = Path.of("build", "e2e");
      Files.createDirectories(dir);
      page.screenshot(
          new Page.ScreenshotOptions()
              .setPath(dir.resolve(label + "-failure.png"))
              .setFullPage(true));
      Files.writeString(dir.resolve(label + "-failure.html"), page.content());
      System.out.printf("[E2E][FAIL] %s url=%s%n", label, page.url());
    } catch (RuntimeException | IOException e) {
      System.out.println("[E2E][FAIL] diagnostics dump failed: " + e);
    }
  }

  /**
   * Expands the page's collapsed filter panel (REQ-FE-021) and waits until its controls are
   * reachable; a no-op when the page has no collapsible panel.
   *
   * @param page the page under test
   */
  /** How long to wait for a filter panel to become reachable after the toggle click. */
  private static final double FILTER_PANEL_TIMEOUT_MS = 10_000;

  static void openFilterPanel(Page page) {
    Locator toggle = page.locator(".filter-toggle").first();
    if (toggle.count() == 0) {
      return;
    }
    if ("false".equals(toggle.getAttribute("aria-expanded"))) {
      toggle.click();
    }
    page.locator(".filter-panel")
        .first()
        .waitFor(new Locator.WaitForOptions().setTimeout(FILTER_PANEL_TIMEOUT_MS));
  }
}
