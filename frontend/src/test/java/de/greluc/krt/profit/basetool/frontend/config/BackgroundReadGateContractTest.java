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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Verifies against the shipped scripts and templates that every background read goes through the
 * one read path, {@code krtFetch.get} / {@code getJson}, which honours the re-authentication and
 * consent gates and refuses a redirected answer (REQ-FE-031, REQ-SEC-012, REQ-SEC-028).
 */
class BackgroundReadGateContractTest {

  /** The polled P4K import module. */
  private static final String P4K_MODULE = "/static/js/p4k-import.js";

  /** The notification bell + unread-badge module. */
  private static final String NOTIFICATIONS_MODULE = "/static/js/notifications.js";

  /** The shared transport module owning {@code krtFetch.get} and {@code window.krtTermsGate}. */
  private static final String KRT_FETCH_MODULE = "/static/js/krt-fetch.js";

  /** The client-error beacon, which must work when {@code krtFetch} did not load. */
  private static final String CLIENT_ERROR_MODULE = "/static/js/krt-client-error.js";

  /** The only scripts allowed to call {@code fetch} themselves. */
  private static final Set<String> TRANSPORT_FILES = Set.of("krt-fetch.js", "krt-client-error.js");

  /** Fewer scanned scripts than this means the walk lost its root. */
  private static final int MIN_SCRIPTS = 100;

  /** Fewer scanned templates than this means the walk lost its root. */
  private static final int MIN_TEMPLATES = 120;

  /**
   * A raw request: a bare or global-object {@code fetch(}, or any {@code XMLHttpRequest}. A method
   * call such as {@code krtFetch.get(} or {@code refetch(} does not match.
   */
  private static final Pattern RAW_REQUEST =
      Pattern.compile(
          "(?<![.\\w$])fetch\\s*\\("
              + "|\\b(?:window|globalThis|self)\\s*\\.\\s*fetch\\b"
              + "|\\bXMLHttpRequest\\s*[.(]|new\\s+XMLHttpRequest\\b");

  /** The {@code get} function of the transport, up to the next function declaration. */
  private static final Pattern TRANSPORT_GET_BODY =
      Pattern.compile("async function get\\(url, opts\\) \\{(.*?)\\n    }\\n", Pattern.DOTALL);

  /** The shared read call in {@code p4k-import.js}. */
  private static final Pattern P4K_SHARED_READ =
      Pattern.compile("window\\.krtFetch\\s*\\.get\\(jobsUrl\\(\\)");

  /** The gate-aware reader hand-off in {@code p4k-import.js}. */
  private static final Pattern P4K_READER_HANDOFF = Pattern.compile("\\.then\\(readJson\\)");

  /** The module's read helper in {@code notifications.js}, delegating to the shared read path. */
  private static final Pattern NOTIFICATIONS_SHARED_READ =
      Pattern.compile(
          "function readNotifications\\(url\\) \\{\\s*return window\\.krtFetch\\.get\\(url");

  /** A call of the read helper in {@code notifications.js} (its declaration excluded). */
  private static final Pattern NOTIFICATIONS_READ_CALL =
      Pattern.compile("(?<!function )readNotifications\\(");

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

  /** The gated-answer branch of the {@code notifications.js} reader, up to its bail-out. */
  private static final Pattern NOTIFICATIONS_GATE_BRANCH =
      Pattern.compile("if \\(!res\\) \\{(.*?)return fallback;", Pattern.DOTALL);

  /** The gated-answer branch of the {@code p4k-import.js} reader. */
  private static final Pattern P4K_GATE_BRANCH =
      Pattern.compile("if \\(!resp\\) return gateTookOver\\(\\);");

  /** The header {@code krt-fetch.js} reads to detect a gated answer. */
  private static final Pattern TERMS_HEADER_READ =
      Pattern.compile("headers\\.get\\('([^']*Terms[^']*)'\\)");

  /**
   * No script besides the transport and the beacon issues a raw request, and no template's inline
   * script does either; ESLint covers the scripts, but an inline block is never linted.
   *
   * @throws IOException if a tree cannot be walked
   * @throws URISyntaxException if a classpath root cannot be resolved
   */
  @Test
  void noScriptOrTemplate_issuesARawRequest() throws IOException, URISyntaxException {
    List<Path> scripts = walk(KRT_FETCH_MODULE, ".js");
    List<Path> templates = walk("/templates/bank-grants.html", ".html");
    assertThat(scripts).as("scripts scanned").hasSizeGreaterThanOrEqualTo(MIN_SCRIPTS);
    assertThat(templates).as("templates scanned").hasSizeGreaterThanOrEqualTo(MIN_TEMPLATES);

    List<String> offenders = new ArrayList<>();
    for (Path script : scripts) {
      if (!TRANSPORT_FILES.contains(script.getFileName().toString())
          && !script.toString().replace('\\', '/').contains("/vendor/")) {
        offenders.addAll(rawRequests(script.getFileName().toString(), Files.readString(script)));
      }
    }
    for (Path template : templates) {
      offenders.addAll(rawRequests(template.getFileName().toString(), Files.readString(template)));
    }
    assertThat(offenders)
        .as("read through krtFetch.get / getJson, write through krtFetch.write / submitForm")
        .isEmpty();
  }

  /** The raw-request matcher must see each shape it exists to reject, and nothing else. */
  @Test
  void theRawRequestMatcher_seesEveryShapeItRejects() {
    assertThat(
            rawRequests(
                "planted.js",
                String.join(
                    "\n",
                    "fetch('/a');",
                    "  return fetch(url, init);",
                    "window.fetch('/b');",
                    "globalThis.fetch('/c');",
                    "const x = new XMLHttpRequest();",
                    "self.fetch('/d');")))
        .hasSize(6);
    assertThat(
            rawRequests(
                "clean.js",
                String.join(
                    "\n",
                    "window.krtFetch.get('/a');",
                    "krtFetch.getJson('/b');",
                    "scheduleRefetch();",
                    "function prefetch() {}",
                    "headers['X-Requested-With'] = 'XMLHttpRequest';")))
        .isEmpty();
  }

  /**
   * The transport's {@code get} offers every answer to both gates and refuses a followed redirect,
   * which is what lets a caller trust a non-null response.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theTransportGet_runsBothGatesAndRefusesRedirects() throws IOException {
    Matcher body = TRANSPORT_GET_BODY.matcher(readResource(KRT_FETCH_MODULE));
    assertThat(body.find())
        .as("krtFetch's get not found in %s (signature renamed?)", KRT_FETCH_MODULE)
        .isTrue();
    assertThat(body.group(1))
        .contains("'X-Requested-With'")
        .contains("'XMLHttpRequest'")
        .contains("maybeReauthenticate(response)")
        .contains("maybeTermsGate(response)")
        .contains("response.redirected");
    assertThat(readResource(CLIENT_ERROR_MODULE))
        .as("the beacon is the one script that may not depend on krtFetch")
        .doesNotContain("krtFetch");
  }

  /**
   * The P4K job poll reads through the shared read path and hands every answer to its reader.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theP4kJobsRead_goesThroughTheSharedReadPath() throws IOException {
    String module = readResource(P4K_MODULE);
    assertThat(count(P4K_SHARED_READ, module)).isEqualTo(1);
    assertThat(count(P4K_READER_HANDOFF, module)).isEqualTo(1);
    assertThat(P4K_GATE_BRANCH.matcher(module).find())
        .as("a gated answer must reach gateTookOver(), which disarms the poll")
        .isTrue();
  }

  /**
   * The three notification reads go through the module's read helper, which delegates to the shared
   * read path, and every answer reaches the gate-aware reader.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theNotificationReads_goThroughTheSharedReadPath() throws IOException {
    String module = readResource(NOTIFICATIONS_MODULE);
    assertThat(NOTIFICATIONS_SHARED_READ.matcher(module).find())
        .as("readNotifications must delegate to window.krtFetch.get")
        .isTrue();
    int reads = count(NOTIFICATIONS_READ_CALL, module);
    assertThat(reads).as("the badge, dropdown and paging reads").isEqualTo(3);
    assertThat(count(NOTIFICATIONS_READER_HANDOFF, module)).isEqualTo(reads);
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
   * The badge poll and the stream must not keep questioning an endpoint whose answer a gate just
   * took over while the next page loads over the departing one.
   *
   * @throws IOException if the module cannot be read from the classpath
   */
  @Test
  void theNotificationBadgePoll_disarmsWhenAGateTakesOver() throws IOException {
    Matcher branch = NOTIFICATIONS_GATE_BRANCH.matcher(readResource(NOTIFICATIONS_MODULE));
    assertThat(branch.find())
        .as("the gated-answer branch not found in %s (anchor renamed?)", NOTIFICATIONS_MODULE)
        .isTrue();
    assertThat(branch.group(1)).contains("stopPolling()").contains("stopSse()");
  }

  /**
   * Neither module may go back to the idiom both carried: {@code ok} is true for a followed
   * redirect, so it reports a login bounce or a consent page as the payload.
   *
   * @throws IOException if a module cannot be read from the classpath
   */
  @Test
  void noBackgroundRead_trustsTheOkFlagAlone() throws IOException {
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
   * the filter sends.
   *
   * @throws IOException if the transport module cannot be read from the classpath
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
   * Lists every raw request in a text as {@code name:line: source}.
   *
   * @param name the file name used in the report
   * @param text the file content
   * @return one entry per offending line
   */
  private static @NotNull List<String> rawRequests(@NotNull String name, @NotNull String text) {
    List<String> found = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (RAW_REQUEST.matcher(lines[i]).find()) {
        found.add(name + ":" + (i + 1) + ": " + lines[i].strip());
      }
    }
    return found;
  }

  /**
   * Walks the classpath directory holding an anchor resource for files with a suffix.
   *
   * @param anchor a resource inside the directory to walk
   * @param suffix the file-name suffix to keep
   * @return the matching files
   * @throws IOException if the tree cannot be walked
   * @throws URISyntaxException if the anchor cannot be resolved to a path
   */
  private static @NotNull List<Path> walk(@NotNull String anchor, @NotNull String suffix)
      throws IOException, URISyntaxException {
    URL url = BackgroundReadGateContractTest.class.getResource(anchor);
    assertThat(url).as("classpath resource %s", anchor).isNotNull();
    Path root = Paths.get(url.toURI()).getParent();
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
    }
  }

  /**
   * Counts the matches of a pattern in a text.
   *
   * @param pattern the pattern to count
   * @param text the text to scan
   * @return the number of matches
   */
  private static int count(@NotNull Pattern pattern, @NotNull String text) {
    Matcher matcher = pattern.matcher(text);
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }

  /**
   * Reads a classpath resource as UTF-8 text, failing the test if it is missing.
   *
   * @param resource the absolute classpath resource path
   * @return the resource content
   * @throws IOException if the resource stream cannot be read
   */
  private static @NotNull String readResource(@NotNull String resource) throws IOException {
    try (InputStream in = BackgroundReadGateContractTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
