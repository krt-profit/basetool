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
 * Build-time enforcement of the client half of the consent-gate contract for the P4K import page's
 * background poll (REQ-SEC-028), plus the poll's own disarm invariant.
 *
 * <p>The server half is covered by {@link TermsAcceptanceGateFilterTest}: a request marked {@code
 * X-Requested-With} is refused with {@code 403} plus the consent-page header, an unmarked one is
 * redirected. The browser side of that contract lives in a static asset that no compiler relates to
 * the filter, and getting it wrong is invisible in every ordinary test: the page keeps working
 * right up until a wording change deploys under an open tab.
 *
 * <p>This module was the worst instance of that. Its poll asked {@code resp.ok}, which fetch makes
 * true for a <em>followed redirect</em> — so the consent page was read as a successful answer — and
 * the timer was only re-evaluated after a successful parse, so it could never disarm itself. A
 * single gated answer therefore became an unbounded 3 s loop that fetched and re-rendered the
 * consent page for as long as the tab stayed open. Three properties keep that shut, and none of
 * them is expressible in Java, so they are pinned against the shipped source here — the technique
 * of {@code PickerSearchLimitsParityTest} and {@code LiveSyncSectionMapParityTest}.
 */
class P4kImportPollGateContractTest {

  /** The polled module under test. */
  private static final String P4K_MODULE = "/static/js/p4k-import.js";

  /** The shared foundation module owning {@code window.krtTermsGate}. */
  private static final String KRT_FETCH_MODULE = "/static/js/krt-fetch.js";

  /** Every call the module makes to the jobs proxy. */
  private static final Pattern FETCH_CALL = Pattern.compile("\\bfetch\\(");

  /** The response reader each of those calls must hand off to. */
  private static final Pattern READ_JSON_HANDOFF = Pattern.compile("\\.then\\(readJson\\)");

  /** The {@code headers:} value of every fetch options object. */
  private static final Pattern HEADERS_VALUE = Pattern.compile("headers:\\s*([^,\\n]+)");

  /**
   * The two spellings that carry the XHR marker. Anything else — an inline object literal above all
   * — silently drops the module back onto the redirect branch of both gates.
   */
  private static final List<String> HEADERS_WITH_MARKER =
      List.of("ajaxHeaders()", "csrfHeaders(ajaxHeaders())");

  /** The defect shape: trusting {@code resp.ok} to mean "this is the payload I asked for". */
  private static final Pattern RESP_OK_TERNARY = Pattern.compile("resp\\.ok\\s*\\?");

  /** The "answer was not job data" branch of the poll, up to its bail-out. */
  private static final Pattern NOT_JOB_DATA_BRANCH =
      Pattern.compile("!Array\\.isArray\\(jobs\\)\\)\\s*\\{(.*?)return;", Pattern.DOTALL);

  /** The header {@code krt-fetch.js} reads to detect a gated answer. */
  private static final Pattern TERMS_HEADER_READ =
      Pattern.compile("headers\\.get\\('([^']*Terms[^']*)'\\)");

  /**
   * Every call to the jobs proxy must send the XHR marker, because the marker is what selects the
   * header contract over a redirect. Without it the consent gate answers a {@code 302}, fetch
   * follows it, and the reader below never sees a gate at all — it sees a 200.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void everyJobsProxyCall_sendsTheMarkerTheGatesBranchOn() throws IOException {
    String module = readResource(P4K_MODULE);

    assertThat(module)
        .as("the XHR marker value must be the one TermsAcceptanceGateFilter#isAjax tests for")
        .contains("'X-Requested-With': 'XMLHttpRequest'");

    List<String> headerValues = captureAll(HEADERS_VALUE, module);
    assertThat(headerValues)
        .as(
            "one headers: entry per fetch in %s (a headerless call is a call without the marker)",
            P4K_MODULE)
        .hasSize(count(FETCH_CALL, module));
    assertThat(headerValues)
        .as("every fetch in %s must build its headers from ajaxHeaders()", P4K_MODULE)
        .allSatisfy(value -> assertThat(HEADERS_WITH_MARKER).contains(value.trim()));
  }

  /**
   * A gated or redirected answer must be recognised as one, not read as job data. This pins the
   * three tests {@code readJson} makes and the absence of the idiom it replaced.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void aGatedAnswer_navigatesInsteadOfCountingAsSuccess() throws IOException {
    String module = readResource(P4K_MODULE);

    assertThat(count(READ_JSON_HANDOFF, module))
        .as("every fetch in %s must read its response through readJson", P4K_MODULE)
        .isEqualTo(count(FETCH_CALL, module));
    assertThat(module)
        .as(
            "readJson must honour the consent gate (REQ-SEC-028) and the re-auth gate"
                + " (REQ-SEC-012) before looking at the body")
        .contains("window.krtTermsGate.check(resp)")
        .contains("window.krtReauth.check(resp)");
    assertThat(module)
        .as("a followed redirect must be rejected explicitly — resp.ok is true for one")
        .contains("resp.redirected || !resp.ok");
    assertThat(RESP_OK_TERNARY.matcher(module).find())
        .as("the `resp.ok ? resp.json() : null` idiom reads any redirect-to-HTML as success")
        .isFalse();
  }

  /**
   * The poll must be able to stop itself. {@code pollControl} is reached only on the success path,
   * so the "not job data" branch is the single place an armed timer can be disarmed after a refusal
   * — and a 3 s timer that cannot disarm is what turned one refusal into an endless loop.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void aRefusedPoll_disarmsItsOwnInterval() throws IOException {
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
   * Closes the loop between the two halves: the header {@code krt-fetch.js} acts on must be the one
   * the filter sends. {@code p4k-import.js} delegates its detection to {@code window.krtTermsGate},
   * so a drift here would silently defeat the call the test above pins.
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
    try (InputStream in = P4kImportPollGateContractTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
