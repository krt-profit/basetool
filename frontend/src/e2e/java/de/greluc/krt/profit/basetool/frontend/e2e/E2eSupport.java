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

  /**
   * Total attempts for the Keycloak login flow before giving up. The OIDC round-trip is the suite's
   * documented high-risk flakiness class (issuer timing under CI load — see {@code
   * docs/e2e-test/README.md}): when the runner is simultaneously building the stack, driving a
   * browser and running the JVM, Keycloak occasionally stalls past the post-credential redirect's
   * 30 s wait, surfacing as a {@link TimeoutError} on an otherwise-correct login. Retrying the
   * whole flow on a freshly re-navigated page absorbs that transient stall; a genuinely broken
   * login still fails every attempt and propagates, so the retry hardens against timing without
   * masking real breakage. Three keeps the worst case bounded well inside the job's 45-minute
   * budget.
   */
  private static final int LOGIN_MAX_ATTEMPTS = 3;

  /**
   * Total attempts for {@link #navigate} before a navigation abort is allowed to propagate. Mirrors
   * {@link #LOGIN_MAX_ATTEMPTS}: attempts {@code 1..N-1} retry a transient abort, the {@code N}-th
   * runs uncaught so a persistent failure still surfaces with its real error. Three bounds the rare
   * WebKit-abort retry loop; since the retry fires only on the abort path it never extends a
   * healthy run.
   */
  private static final int NAVIGATE_MAX_ATTEMPTS = 3;

  /**
   * Settle pause, in milliseconds, between a transient navigation abort and the retry. It gives the
   * reset HTTP/2 stream time to tear down so the re-issued GET opens a fresh one. Kept short
   * because the WebKit {@code INTERNAL_ERROR} reset clears almost immediately, and it only ever
   * runs on the abort path.
   */
  private static final int NAVIGATE_RETRY_BACKOFF_MILLIS = 500;

  /**
   * Per-attempt navigation timeout, in milliseconds, applied to each {@code page.navigate(...)} in
   * {@link #navigate}. Set above Playwright's 30 s default so a slow-but-progressing document load
   * on a contended CI runner is not cut off prematurely; when an attempt does exceed it, {@link
   * #navigate} treats the {@link TimeoutError} as transient and retries on a fresh GET rather than
   * failing outright. The {@link #NAVIGATE_MAX_ATTEMPTS} bound keeps the worst case finite.
   */
  private static final double NAVIGATE_TIMEOUT_MILLIS = 45_000;

  /**
   * Lowercase substrings that mark a {@code page.navigate(...)} failure as a transient connection
   * reset of the navigation request rather than a genuine page failure (see {@link
   * #isTransientNavigationAbort}): WebKit's HTTP/2 {@code INTERNAL_ERROR} and its {@code
   * frameAbortedNavigation} wrapper, Firefox's {@code NS_BINDING_ABORTED} and {@code
   * NS_ERROR_ABORT} (Gecko aborts a main-frame navigation under CI load with either code — the
   * latter surfaced as the dominant Firefox-only flake), Chromium's {@code net::ERR_ABORTED} and
   * {@code net::ERR_HTTP2_PROTOCOL_ERROR} (the latter is Chromium's surfacing of an HTTP/2 stream
   * reset mid-navigation under CI load — the same root cause as WebKit's {@code INTERNAL_ERROR},
   * with the frontend's Tomcat logging the peer-side symptom as {@code
   * AsyncRequestNotUsableException} while having already served the document {@code 200}), and
   * Playwright's own "Navigation interrupted by another one". Deliberately narrow so only a genuine
   * abort is retried and every real error propagates unmasked.
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
   * Launches a headless browser of the engine named by the {@code e2e.browser} system property —
   * {@code chromium} (default), {@code firefox}, or {@code webkit}. This is the single seam the
   * test classes use, so the whole suite runs against any one engine per JVM (a CI matrix fans the
   * three out in parallel).
   *
   * <p>For the ephemeral local stack the Keycloak issuer host {@code host.docker.internal} must
   * resolve to the loopback (the stack publishes Keycloak on 127.0.0.1). Each engine needs a
   * different mechanism, all gated on {@code managesStack}:
   *
   * <ul>
   *   <li><b>Chromium</b> — the {@code --host-resolver-rules} launch arg (override via {@code
   *       -De2e.hostResolverRules}).
   *   <li><b>Firefox</b> — the {@code network.dns.localDomains} preference.
   *   <li><b>WebKit</b> — no launch-level override exists; it relies on the OS hosts file mapping
   *       {@code host.docker.internal} to 127.0.0.1 (CI adds the entry — see {@code
   *       docs/e2e-test/README.md}; on a workstation it must be added manually).
   * </ul>
   *
   * <p>Against an external deployment ({@code !managesStack}) no remap is applied for any engine —
   * the real hostname resolves normally.
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
   * Launches headless Firefox pinned to a fresh HTTP/1.1 connection per request and, for the
   * ephemeral stack, with {@code host.docker.internal} resolved to the loopback.
   *
   * <p><b>The uniform fix now lives at the server: the E2E stack disables HTTP/2 on the
   * frontend</b> ({@code SERVER_HTTP2_ENABLED=false} in {@code docker-compose.e2e.yml}), so every
   * engine speaks HTTP/1.1 and the stream-reset class below cannot arise for any of them. The two
   * Firefox prefs here are retained on top of that as a Firefox-specific guard (the HTTP/1.1
   * connection-reuse race called out below) plus defence-in-depth, and because they are what
   * originally root-caused the flake; the narrative is kept for that history.
   *
   * <p>Two transport preferences together were the original root-cause fix for the suite's dominant
   * Firefox-only flake — the bank wipe/deposit {@code waitForResponse} hangs that survived raising
   * the timeout to 60 s. The frontend then served <b>HTTP/2</b> (WebKit reports its aborts as
   * {@code HTTP/2 Error: INTERNAL_ERROR}), and under CI load Gecko's h2 stack <b>reset the POST
   * stream mid-flight</b>: the captured deposit reset truncated the request body (the frontend
   * logged {@code POST /api/proxy/bank/deposits -> 400 "I/O error while reading input message"})
   * and the wipe reset dropped the response after the server had already committed {@code 200}.
   * Either way the browser's {@code fetch()} rejects with a <em>network error</em> rather than
   * delivering an HTTP response — the in-place handler shows its generic failure toast, and
   * Playwright's {@code waitForResponse} never matches and hangs to its timeout. Chromium tolerated
   * the reset and Firefox did not (and, unlike an idempotent GET, never auto-retries a
   * non-idempotent POST), so this was first fixed per-browser; WebKit was later observed to hit the
   * <em>same</em> reset (Tomcat "Client reset the stream before the request was fully read") but
   * has no Playwright-level h2 toggle, which is why the fix moved to the server above.
   *
   * <ul>
   *   <li><b>{@code network.http.http2.enabled = false}</b> drops Firefox to HTTP/1.1, eliminating
   *       the h2 stream-reset entirely. {@code network.http.keep-alive} is an HTTP/1.1-only knob,
   *       so it could never touch the h2 streams that were actually failing — hence the timeout
   *       bump and a bare keep-alive toggle did not help.
   *   <li><b>{@code network.http.keep-alive = false}</b> then forces a fresh connection per request
   *       on that HTTP/1.1 transport, so the analogous HTTP/1.1 connection-reuse race (Gecko
   *       reusing a persistent connection the instant the server closes it → {@code NS_ERROR_ABORT}
   *       / {@code NS_BINDING_ABORTED}) cannot occur either.
   * </ul>
   *
   * <p>Together they give every request — XHR and navigation alike — a clean, dedicated HTTP/1.1
   * connection, complementing {@link #navigate}'s abort retry rather than relying on it. The cost
   * is a few extra localhost TLS handshakes, negligible for the suite, and this hardens transport
   * only: it changes neither the app nor any assertion, so a genuine failure still fails. The
   * notification SSE is unaffected — its response streams open over its own HTTP/1.1 connection.
   *
   * <p>For the ephemeral stack {@code network.dns.localDomains} additionally maps the Keycloak
   * issuer host to the loopback (Firefox has no {@code --host-resolver-rules} equivalent).
   *
   * @param playwright the Playwright entry point
   * @param managesStack whether the ephemeral stack is in play (enables the local-domain remap)
   * @return a launched headless Firefox browser
   */
  private static Browser launchFirefox(Playwright playwright, boolean managesStack) {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put("network.http.http2.enabled", false);
    prefs.put("network.http.keep-alive", false);
    if (managesStack) {
      prefs.put("network.dns.localDomains", "host.docker.internal");
    }
    BrowserType.LaunchOptions options =
        new BrowserType.LaunchOptions().setHeadless(true).setFirefoxUserPrefs(prefs);
    return playwright.firefox().launch(options);
  }

  /**
   * Launches headless WebKit. WebKit has neither a {@code --host-resolver-rules} arg nor a DNS
   * preference, so for the ephemeral stack it relies on the OS hosts file mapping {@code
   * host.docker.internal} to 127.0.0.1; this fails fast with an actionable message when that
   * mapping is absent rather than surfacing an opaque "Could not connect to server" mid-flow.
   *
   * <p>WebKit likewise has no Playwright-level HTTP/2 toggle, so it does not carry the h2 opt-out
   * the {@link #launchFirefox} prefs give Gecko; it instead depends on the E2E stack serving
   * HTTP/1.1 ({@code SERVER_HTTP2_ENABLED=false}, see {@code docker-compose.e2e.yml}) to avoid the
   * POST stream-reset flake documented on {@link #launchFirefox}.
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
   * Verifies that {@code host.docker.internal} resolves to the loopback — the precondition for
   * WebKit to reach the ephemeral stack's Keycloak. The OS resolver (and therefore WebKit) sees
   * whatever this check sees, so an actionable error here pre-empts an opaque connection failure
   * mid-flow.
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
   * Drives the Keycloak default-theme login form and waits for the redirect back to the frontend
   * origin, retrying the whole flow up to {@link #LOGIN_MAX_ATTEMPTS} times when an attempt times
   * out. A timed-out {@code waitForURL} means Keycloak never issued the redirect back and so set no
   * authenticated session, which makes the retry safe and effective: re-navigating to the
   * authorization endpoint on the same page reliably shows the form again (and aborts any
   * navigation the stalled attempt left in flight), so a fresh attempt clears the transient
   * issuer-timing stall. Because login is read-only this never re-runs a destructive mutation —
   * unlike a whole-test retry — and every login in the suite gets the same resilience: the {@link
   * #authenticatedStorageState} callers funnel through here, as do the multi-user tests that drive
   * {@code login} directly. The final attempt's {@link TimeoutError} propagates unchanged, so a
   * genuinely broken login is not masked.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param username Keycloak username
   * @param password Keycloak password
   * @throws TimeoutError if the login does not complete within {@link #LOGIN_MAX_ATTEMPTS} attempts
   */
  static void login(Page page, String baseUrl, String username, String password) {
    // Attempts 1..N-1 retry on a timeout; the final attempt runs uncaught so a persistent
    // failure propagates rather than being swallowed. Structuring it this way keeps the loop
    // condition the genuine bound (instead of an in-body throw that the linter — rightly —
    // flags as making the condition always true).
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
   * Performs a single Keycloak login attempt: navigates to the authorization endpoint (which aborts
   * any navigation a previous attempt left in flight and re-renders the default-theme form), fills
   * and submits the credentials, then waits up to 30 s for the redirect back to the frontend
   * origin. Extracted from {@link #login} so the retry loop there can re-run the complete flow on a
   * clean navigation rather than from a half-redirected page.
   *
   * @param page the page to drive
   * @param baseUrl the frontend origin
   * @param username Keycloak username
   * @param password Keycloak password
   * @throws TimeoutError if the form never appears or the redirect back to {@code baseUrl} does not
   *     arrive within 30 s
   */
  private static void attemptLogin(Page page, String baseUrl, String username, String password) {
    page.navigate(baseUrl + "/oauth2/authorization/keycloak");
    page.waitForSelector("#username");
    page.fill("#username", username);
    page.fill("#password", password);
    page.click("#kc-login");
    page.waitForURL(
        url -> url.startsWith(baseUrl), new Page.WaitForURLOptions().setTimeout(30_000));
  }

  /**
   * Logs in through Keycloak and returns the path to a saved Playwright storageState, so subsequent
   * contexts in the same test class can start already authenticated instead of re-running the OIDC
   * flow. Each call performs a fresh login (its own frontend session) — the result is deliberately
   * NOT memoised across test classes, so flows stay isolated and never share a mutated session.
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
   * Clicks a submit control that can be covered by the {@code position: fixed} global footer
   * ({@code .krt-footer}, introduced with the das-kartell design system). A long form's bottom
   * submit button sits behind the footer, so a coordinate click is intercepted and times out. The
   * alternatives each failed on at least one engine — {@code dispatchEvent} is untrusted, {@code
   * press("Enter")} does not activate these submits on WebKit, and {@code requestSubmit()} aborted
   * the app's submit. So this drops the footer out of the way (it is irrelevant to the submit flow,
   * and the page-side {@code evaluate} runs fine under the strict CSP — unlike string-predicate
   * {@code eval}), then performs a normal, trusted click that submits with full validation,
   * consistently across Chromium, Firefox and WebKit.
   *
   * <p>The click is wrapped in {@link #awaitFormPost} so the method only returns once the
   * post-submit navigation (including the redirect it triggers) has settled — see that method for
   * why a bare click would otherwise let a follow-up {@code navigate(...)} abort the in-flight
   * submit on WebKit.
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
   * Picks an option from a KRT searchable combobox (krt-searchable-select.js) by the option's
   * value. The enhancer replaces the original {@code <select>} with a filtering text input plus a
   * {@code role=listbox}, so selecting is no longer {@code selectOption(...)}: this focuses the
   * textbox to open the popup, then clicks the {@code role=option} carrying the wanted value in its
   * {@code data-value} (the enhancer mirrors each option's value there). The option lookup is
   * scoped to this combobox's own wrapper (the textbox's parent): closing a combobox merely hides
   * its rendered options, so a page-global lookup could match the stale, hidden option list of a
   * combobox opened earlier and time out waiting for it to become clickable. Used wherever a {@code
   * <select>} was converted into a searchable picker (REQ-FE-011).
   *
   * @param comboInput the visible combobox textbox the enhancer rendered (e.g. located by its
   *     {@code data-testid})
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
   * Picks the first real option of a KRT searchable combobox — the equivalent of a native {@code
   * selectOption(new SelectOption().setIndex(1))}, since the combobox does not render its
   * empty-value placeholder as a list option. Opens the popup by focusing the textbox, then clicks
   * the first {@code role=option} in this combobox's own wrapper — scoped like {@link
   * #selectComboboxByValue} so another combobox's stale, hidden option list on the same page cannot
   * shadow the match.
   *
   * @param comboInput the visible combobox textbox the enhancer rendered
   */
  static void selectComboboxFirstOption(Locator comboInput) {
    comboInput.click();
    comboInput.locator("xpath=..").locator("li[role='option']").first().click();
  }

  /**
   * Runs a submit action that triggers a full-page form POST and blocks until the resulting
   * navigation — <em>including the redirect the POST triggers</em> — has fully settled, so the
   * submit and the page it lands on cannot be dropped by whatever the test does next.
   *
   * <p>Playwright's {@code locator.click()} returns once the click is dispatched — it does NOT wait
   * for any navigation the click starts. The original pattern ({@code click()} then {@code
   * waitForLoadState()}) was racy: when the POST had not yet committed, {@code waitForLoadState()}
   * saw the still-current form document (already in the {@code load} state) and resolved
   * immediately, so the test's next {@code navigate(...)} aborted the in-flight POST and the
   * mutation was silently lost — the flaky, engine-specific "row not visible" failures.
   *
   * <p>Waiting only for the POST's own {@code 3xx} response (an earlier, incomplete fix) is also
   * insufficient: the browser then follows that redirect with a GET for the post-submit document,
   * and a {@code navigate(...)} fired while that GET is still in flight aborts it. WebKit surfaces
   * the aborted navigation as {@code HTTP/2 Error: INTERNAL_ERROR} ({@code frameAbortedNavigation})
   * — the failure this method now guards against.
   *
   * <p>So this waits for the <strong>settled</strong> post-submit document response: the redirect
   * target's GET (the normal Post/Redirect/Get path), or — defensively — a non-{@code 3xx} POST
   * document for a submit that renders its own page without redirecting (which keeps the wait from
   * hanging for a redirect that never comes). Either way the backend mutation has provably landed
   * and no navigation is in flight, so any subsequent {@code navigate(...)} is safe. The {@code
   * isNavigationRequest} and {@code document} guards restrict the match to the top-level
   * navigation, excluding XHR/WebSocket/beacon and subresource requests.
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
          // Post/Redirect/Get happy path: the app answers the POST with a 3xx and the browser
          // follows it with a GET for the post-submit document. Waiting for that GET's response —
          // not the POST's 3xx — means the redirect has fully committed, so the caller's next
          // navigate(...) has no in-flight redirect GET to abort.
          if ("GET".equals(request.method())) {
            return true;
          }
          // Defensive: a submit that renders its own document (no redirect) settles on the POST
          // response itself, so accept any non-3xx POST document too rather than hang waiting for
          // a redirect that will never arrive.
          int status = response.status();
          return "POST".equals(request.method()) && (status < 300 || status >= 400);
        },
        new Page.WaitForResponseOptions().setTimeout(15_000),
        submitAction);
  }

  /**
   * Navigates to {@code url}, retrying up to {@link #NAVIGATE_MAX_ATTEMPTS} times when the
   * navigation request is aborted by a transient, engine-level connection reset rather than the
   * target page genuinely failing.
   *
   * <p>This hardens the post-submit list re-load that several flows perform immediately after
   * {@link #awaitFormPost} / {@link #clickSubmitClearingFooter}, and every other content navigation
   * the suite drives. {@code awaitFormPost} already blocks until the submit's Post/Redirect/Get has
   * fully settled — so the mutation is safe and no submit navigation is in flight — but the very
   * next {@code navigate(...)} still issues a fresh GET on a connection the browser has, under CI
   * load, been seen to tear down mid-flight. The engines surface that aborted main-frame navigation
   * differently: WebKit as {@code HTTP/2 Error: INTERNAL_ERROR} ({@code frameAbortedNavigation}),
   * Firefox as {@code NS_ERROR_ABORT} / {@code NS_BINDING_ABORTED}, Chromium as {@code
   * net::ERR_ABORTED} or — when the HTTP/2 stream is reset mid-navigation — {@code
   * net::ERR_HTTP2_PROTOCOL_ERROR}, all thrown as a {@link PlaywrightException} (concretely a
   * {@code DriverException}). The reset is transient — a brief settle plus a fresh GET succeeds —
   * so {@link #isTransientNavigationAbort} gates the abort retry to exactly those signatures and
   * lets every other navigation failure (a real 4xx/5xx document, a wrong URL) propagate
   * immediately and unmasked.
   *
   * <p>It is also timeout-tolerant: each attempt is bounded by {@link #NAVIGATE_TIMEOUT_MILLIS}
   * (above Playwright's 30 s default), and a {@link TimeoutError} from a slow-but-progressing load
   * on a contended runner is retried on a fresh GET rather than failing the test outright. The
   * final attempt runs uncaught, so a persistent abort or timeout still fails the test with the
   * genuine Playwright error.
   *
   * @param page the page to navigate
   * @param url the absolute URL to load
   * @return the main-frame {@link Response} of the successful navigation (never {@code null} for a
   *     document load), so callers can assert on its HTTP status — e.g. that a reopened page
   *     renders {@code 200} rather than {@code 500}
   * @throws PlaywrightException if every attempt is aborted or times out, or on the first
   *     non-transient navigation failure
   */
  static Response navigate(Page page, String url) {
    Page.NavigateOptions options = new Page.NavigateOptions().setTimeout(NAVIGATE_TIMEOUT_MILLIS);
    // Attempts 1..N-1 retry a transient abort or timeout; the final attempt (below the loop) runs
    // uncaught, so a persistent failure propagates with its real error — mirrors
    // login()/attemptLogin.
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
      // Let the reset stream tear down and the page fall back to its prior, already-loaded document
      // before re-issuing the GET. The settle runs between attempts, outside the catch, so it can
      // never mask the abort/timeout it follows. The load-state wait is best-effort and explicitly
      // bounded: the next attempt issues a fresh GET that establishes its own load state, so a
      // settle that itself outruns the timeout on a contended runner must not become the test's
      // failure — swallow that TimeoutError and retry. Only the final attempt (below the loop)
      // propagates a genuine navigation failure.
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
   * Reports whether a {@link PlaywrightException} from {@code page.navigate(...)} is a transient,
   * retryable abort of the navigation request itself — as opposed to a genuine failure of the
   * target page. It matches only the engine-specific abort signatures in {@link
   * #NAVIGATION_ABORT_SIGNATURES} (WebKit's {@code INTERNAL_ERROR} / {@code
   * frameAbortedNavigation}, Firefox's {@code NS_BINDING_ABORTED} / {@code NS_ERROR_ABORT},
   * Chromium's {@code ERR_ABORTED} / {@code ERR_HTTP2_PROTOCOL_ERROR}, and Playwright's "Navigation
   * interrupted by another one"). A timeout, a 4xx/5xx, a DNS error or any other navigation failure
   * carries none of these, so it is reported non-transient and {@link #navigate} lets it propagate
   * unmasked.
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
}
