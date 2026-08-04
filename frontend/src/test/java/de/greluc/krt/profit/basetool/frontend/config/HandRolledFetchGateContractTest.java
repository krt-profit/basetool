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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time enforcement of the client half of the consent-gate contract (REQ-SEC-028) for the
 * background reads that bypass {@code krtFetch}, plus the self-disarm invariant of the two timers
 * driving them.
 *
 * <p>The server half is covered by {@link TermsAcceptanceGateFilterTest}: a request marked {@code
 * X-Requested-With} is refused with {@code 403} plus the consent-page header, an unmarked one is
 * redirected. Writes reach that contract through {@code krtFetch}, which makes both gate checks
 * centrally. A hand-rolled {@code fetch} does not — and nothing in Java relates a static asset to
 * the filter, so a module that skips the check keeps working right up until a wording change
 * deploys under an open tab, at which point it fails in a way no test and no error message names.
 *
 * <p>Two modules do their reads that way, and each got it wrong differently:
 *
 * <ul>
 *   <li>{@code p4k-import.js} sent no marker at all, so the gate answered its 3 s job poll with a
 *       {@code 302}; fetch followed it and {@code resp.ok} was <b>true</b> for the consent page, so
 *       a refusal was read as job data. Its timer is re-evaluated only after a successful parse, so
 *       the refusal also left it armed — one gated answer became an unbounded loop that re-fetched
 *       and re-rendered the consent page every tick for as long as the tab stayed open.
 *   <li>{@code notifications.js} sent the marker and handled the re-auth gate, but never asked
 *       about the consent one: the {@code 403} simply fell through its {@code res.ok} test, so the
 *       unread badge froze at its last value and the bell dropdown opened empty, on every tab in
 *       that state, with nothing on screen saying why.
 * </ul>
 *
 * <p>The properties that keep both shut are not expressible in Java, so they are pinned against the
 * shipped sources here — the technique of {@code PickerSearchLimitsParityTest} and {@code
 * LiveSyncSectionMapParityTest}.
 */
class HandRolledFetchGateContractTest {

  /** The polled P4K import module. */
  private static final String P4K_MODULE = "/static/js/p4k-import.js";

  /** The notification bell + unread-badge module. */
  private static final String NOTIFICATIONS_MODULE = "/static/js/notifications.js";

  /** The shared foundation module owning {@code window.krtTermsGate}. */
  private static final String KRT_FETCH_MODULE = "/static/js/krt-fetch.js";

  /** The XHR marker the gates branch on, as it must appear in a module's request headers. */
  private static final String XHR_MARKER = "'X-Requested-With': 'XMLHttpRequest'";

  /** Every hand-rolled call a module makes. */
  private static final Pattern FETCH_CALL = Pattern.compile("\\bfetch\\(");

  /** The {@code headers:} value of every fetch options object in {@code p4k-import.js}. */
  private static final Pattern P4K_HEADERS_VALUE = Pattern.compile("headers:\\s*([^,\\n]+)");

  /**
   * The two spellings that carry the marker in {@code p4k-import.js}. Anything else — an inline
   * object literal above all — silently drops the module back onto the redirect branch of both
   * gates.
   */
  private static final List<String> P4K_HEADERS_WITH_MARKER =
      List.of("ajaxHeaders()", "csrfHeaders(ajaxHeaders())");

  /** The request-init helper every {@code notifications.js} read must pass. */
  private static final Pattern NOTIFICATIONS_MARKED_CALL =
      Pattern.compile("fetch\\([^)]*,\\s*csrfRequestInit\\(\\)\\)");

  /** The gate-aware reader hand-off in {@code p4k-import.js}. */
  private static final Pattern P4K_READER_HANDOFF = Pattern.compile("\\.then\\(readJson\\)");

  /**
   * The gate-aware reader hand-off in {@code notifications.js}. Anchored on {@code return} so the
   * reader's own declaration is not counted as one of its call sites.
   */
  private static final Pattern NOTIFICATIONS_READER_HANDOFF =
      Pattern.compile("return readJson\\(res,");

  /**
   * The defect shape both modules carried: trusting {@code ok} to mean "this is the payload I asked
   * for", when fetch makes it true for a followed redirect as well.
   */
  private static final Pattern OK_TERNARY = Pattern.compile("\\bres(?:p)?\\.ok\\s*\\?");

  /** The "answer was not job data" branch of the P4K poll, up to its bail-out. */
  private static final Pattern NOT_JOB_DATA_BRANCH =
      Pattern.compile("!Array\\.isArray\\(jobs\\)\\)\\s*\\{(.*?)return;", Pattern.DOTALL);

  /** The consent-gate branch of the {@code notifications.js} reader, up to its bail-out. */
  private static final Pattern NOTIFICATIONS_GATE_BRANCH =
      Pattern.compile("krtTermsGate\\.check\\(res\\)\\)\\s*\\{(.*?)return", Pattern.DOTALL);

  /** The header {@code krt-fetch.js} reads to detect a gated answer. */
  private static final Pattern TERMS_HEADER_READ =
      Pattern.compile("headers\\.get\\('([^']*Terms[^']*)'\\)");

  /**
   * Every P4K jobs-proxy call must send the marker — it is what selects the header contract over a
   * redirect — and must read its answer through the gate-aware reader.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theP4kJobsCalls_areMarkedAndReadThroughTheGate() throws IOException {
    String module = readResource(P4K_MODULE);

    assertThat(module)
        .as("the marker value must be the one TermsAcceptanceGateFilter#isAjax tests for")
        .contains(XHR_MARKER);

    List<String> headerValues = captureAll(P4K_HEADERS_VALUE, module);
    assertThat(headerValues)
        .as(
            "one headers: entry per fetch in %s (a headerless call is a call without the marker)",
            P4K_MODULE)
        .hasSize(count(FETCH_CALL, module));
    assertThat(headerValues)
        .as("every fetch in %s must build its headers from ajaxHeaders()", P4K_MODULE)
        .allSatisfy(value -> assertThat(P4K_HEADERS_WITH_MARKER).contains(value.trim()));

    assertGateAwareReader(module, P4K_MODULE, P4K_READER_HANDOFF);
  }

  /**
   * The same for the three notification reads, whose writes go through {@code krtFetch} but whose
   * badge / dropdown / paging GETs do not.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theNotificationReads_areMarkedAndReadThroughTheGate() throws IOException {
    String module = readResource(NOTIFICATIONS_MODULE);

    assertThat(module)
        .as("csrfRequestInit must carry the marker the gates branch on")
        .contains(XHR_MARKER);
    assertThat(count(NOTIFICATIONS_MARKED_CALL, module))
        .as(
            "every fetch in %s must pass csrfRequestInit(), which carries the marker",
            NOTIFICATIONS_MODULE)
        .isEqualTo(count(FETCH_CALL, module));

    assertGateAwareReader(module, NOTIFICATIONS_MODULE, NOTIFICATIONS_READER_HANDOFF);
  }

  /**
   * The P4K poll must be able to stop itself. {@code pollControl} is reached only on the success
   * path, so the "not job data" branch is the single place an armed timer can be disarmed after a
   * refusal — and a 3 s timer that cannot disarm is what turned one refusal into an endless loop.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theP4kPoll_disarmsItsOwnInterval() throws IOException {
    String module = readResource(P4K_MODULE);

    Matcher branch = NOT_JOB_DATA_BRANCH.matcher(module);
    assertThat(branch.find())
        .as("the !Array.isArray(jobs) branch not found in %s (anchor renamed?)", P4K_MODULE)
        .isTrue();
    assertThat(branch.group(1))
        .as("an answer that is not job data must disarm the poll before bailing out")
        .contains("stopPolling()");
    assertThat(count(Pattern.compile("clearInterval\\("), module))
        .as("clearInterval must live in stopPolling alone, so every bail-out shares one disarm")
        .isEqualTo(1);
    assertThat(count(Pattern.compile("setInterval\\("), module))
        .as("the poll must be armed in exactly one place (pollControl)")
        .isEqualTo(1);
  }

  /**
   * The badge poll must not keep questioning an endpoint that just refused it while the consent
   * page loads over the departing page.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theNotificationBadgePoll_disarmsWhenTheConsentGateTakesOver() throws IOException {
    Matcher branch = NOTIFICATIONS_GATE_BRANCH.matcher(readResource(NOTIFICATIONS_MODULE));
    assertThat(branch.find())
        .as("the consent-gate branch not found in %s (anchor renamed?)", NOTIFICATIONS_MODULE)
        .isTrue();
    assertThat(branch.group(1))
        .as("a gated answer must stop the badge poll before bailing out")
        .contains("stopPolling()");
  }

  /**
   * Neither module may go back to the idiom both carried: {@code ok} is true for a followed
   * redirect, so it reports a login bounce or a consent page as the payload.
   *
   * @throws IOException if a module cannot be read from the classpath
   */
  @Test
  void noHandRolledRead_trustsTheOkFlagAlone() throws IOException {
    for (String resource : List.of(P4K_MODULE, NOTIFICATIONS_MODULE)) {
      assertThat(OK_TERNARY.matcher(readResource(resource)).find())
          .as(
              "the `res.ok ? res.json() : x` idiom reads any redirect-to-HTML as success (%s)",
              resource)
          .isFalse();
    }
  }

  /**
   * Closes the loop between the two halves: the header {@code krt-fetch.js} acts on must be the one
   * the filter sends. Both modules delegate their detection to {@code window.krtTermsGate}, so a
   * drift here would silently defeat every call the tests above pin.
   *
   * @throws IOException if the foundation module cannot be read from the classpath
   */
  @Test
  void theHeaderKrtFetchActsOn_isTheOneTheGateSends() throws IOException {
    Matcher matcher = TERMS_HEADER_READ.matcher(readResource(KRT_FETCH_MODULE));
    assertThat(matcher.find())
        .as("the consent-header read not found in %s (anchor renamed?)", KRT_FETCH_MODULE)
        .isTrue();
    assertThat(matcher.group(1))
        .as("krtTermsGate reads a header the gate never sends")
        .isEqualTo(TermsAcceptanceGateFilter.TERMS_GATE_HEADER);
  }

  /**
   * Asserts that a module reads every one of its responses through a reader that offers it to both
   * gates and rejects a followed redirect outright.
   *
   * @param module the module source
   * @param resource the module's classpath path, for failure messages
   * @param handoff the pattern matching the module's hand-off to that reader, once per call
   */
  private static void assertGateAwareReader(String module, String resource, Pattern handoff) {
    assertThat(count(handoff, module))
        .as("every fetch in %s must read its response through the gate-aware reader", resource)
        .isEqualTo(count(FETCH_CALL, module));
    assertThat(module)
        .as(
            "%s must honour the consent gate (REQ-SEC-028) and the re-auth gate (REQ-SEC-012)"
                + " before looking at the body",
            resource)
        .containsPattern("krtTermsGate\\.check\\(res")
        .containsPattern("krtReauth\\.check\\(res");
    assertThat(module)
        .as("%s must reject a followed redirect explicitly — ok is true for one", resource)
        .containsPattern("res(?:p)?\\.redirected \\|\\| !res(?:p)?\\.ok");
  }

  /**
   * Counts the matches of a pattern in a text.
   *
   * @param pattern the pattern to count
   * @param text the text to scan
   * @return the number of matches
   */
  private static int count(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }

  /**
   * Collects group 1 of every match of a pattern in a text.
   *
   * @param pattern the pattern whose first group is collected
   * @param text the text to scan
   * @return the captured values, in source order
   */
  private static List<String> captureAll(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    List<String> values = new ArrayList<>();
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }

  /**
   * Reads a classpath resource as UTF-8 text, failing the test if it is missing so a moved or
   * renamed asset breaks this gate loudly instead of silently emptying the scanned text.
   *
   * @param resource the absolute classpath resource path
   * @return the resource content
   * @throws IOException if the resource stream cannot be read
   */
  private static String readResource(String resource) throws IOException {
    try (InputStream in = HandRolledFetchGateContractTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
