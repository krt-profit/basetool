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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.controller.ClientErrorReportController.ClientErrorReport;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Unit tests for {@link ClientErrorReportController}, the server half of the M12 client-error
 * beacon.
 *
 * <p>The endpoint's whole risk profile is that the payload is attacker-controllable <em>and</em>
 * self-triggerable, so the assertions here are deliberately about the guardrails rather than the
 * happy path: the level is the contract (DEBUG, because INFO/WARN would flood and the ERROR path
 * would trip {@code LogbackErrorSpike}), the {@code kind} tag must come from the server-side
 * allowlist so it can never become an unbounded label, a script URL must lose its query string
 * server-side, and control characters in the message must not survive into the log (CWE-117).
 */
class ClientErrorReportControllerTest {

  /** The shipped beacon module, read off the test classpath to pin the client/server kind set. */
  private static final String BEACON_MODULE = "/static/js/krt-client-error.js";

  /**
   * Captures the beacon's request-body object literal — the argument of its object-literal {@code
   * JSON.stringify} call. That literal has no nested braces, so the brace-excluding body class is
   * exact here, and the module's other {@code JSON.stringify(budget)} call cannot match it because
   * it passes a variable rather than a literal.
   */
  private static final Pattern BEACON_BODY = Pattern.compile("JSON\\.stringify\\(\\{([^}]*)\\}\\)");

  /** Matches one {@code name:} property key inside the captured object literal. */
  private static final Pattern OBJECT_KEY = Pattern.compile("(\\w+)\\s*:");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ClientErrorReportController controller =
      new ClientErrorReportController(meterRegistry);

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(ClientErrorReportController.class);
    logger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void acceptedReport_isCountedUnderItsKindAndAnsweredWith204() {
    ResponseEntity<Void> response =
        controller.report(
            new ClientErrorReport(
                "Cannot read properties of null",
                "https://app/js/missions.js",
                42,
                7,
                MetricNames.CLIENT_ERROR_SCRIPT_ERROR));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(counterFor(MetricNames.CLIENT_ERROR_SCRIPT_ERROR)).isEqualTo(1.0d);
  }

  @Test
  void report_isLoggedAtDebugAndNeverAtInfoOrAbove() {
    controller.report(
        new ClientErrorReport(
            "boom", "https://app/js/a.js", 1, 2, MetricNames.CLIENT_ERROR_UNHANDLED_REJECTION));

    // The level IS the contract here: this endpoint is self-triggerable at frame rate, so anything
    // above DEBUG is a log-flood vector and an ERROR would additionally trip LogbackErrorSpike.
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @ParameterizedTest
  @ValueSource(strings = {"stack_trace", "SCRIPT_ERROR", "", "script_error ", "../../etc"})
  void unknownKind_isRejectedAndCreatesNoMeterSeries(String kind) {
    ResponseEntity<Void> response =
        controller.report(new ClientErrorReport("boom", "https://app/js/a.js", 1, 2, kind));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // Not merely "count stayed 0": no meter must exist at all, or a crafted kind would still
    // create an unbounded, attacker-chosen label series (REQ-OBS-006).
    assertThat(meterRegistry.find(MetricNames.CLIENT_ERROR).counters()).isEmpty();
  }

  @Test
  void missingBody_isRejectedWithoutCounting() {
    ResponseEntity<Void> response = controller.report(null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(meterRegistry.find(MetricNames.CLIENT_ERROR).counters()).isEmpty();
    assertThat(appender.list).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
  }

  @Test
  void sourceQueryStringAndFragmentAreStrippedServerSide() {
    controller.report(
        new ClientErrorReport(
            "boom",
            "https://app/js/a.js?access_token=super-secret#L42",
            1,
            2,
            MetricNames.CLIENT_ERROR_SCRIPT_ERROR));

    String line = appender.list.getFirst().getFormattedMessage();
    assertThat(line).contains("source=https://app/js/a.js");
    assertThat(line).doesNotContain("super-secret").doesNotContain("access_token");
  }

  @Test
  void hostileMessageIsSanitisedAndTruncatedBeforeItReachesTheLog() {
    String forged = "harmless\nERROR --- forged log line from a client" + "x".repeat(400);

    controller.report(
        new ClientErrorReport(
            forged, "https://app/js/a.js", 1, 2, MetricNames.CLIENT_ERROR_RESOURCE_ERROR));

    String line = appender.list.getFirst().getFormattedMessage();
    // No newline may survive: a member pasting one could otherwise fabricate a second log line
    // that reads as genuine during an incident triage (CWE-117).
    assertThat(line).doesNotContain("\n");
    // Truncated well below the raw 400+ characters the caller sent.
    assertThat(line.length()).isLessThan(forged.length());
  }

  @Test
  void unreadableBody_isAnsweredWith400AtDebugSoItCannotFloodTheErrorLog() {
    ResponseEntity<Void> response =
        controller.handleUnreadableBody(new HttpMessageNotReadableException("malformed", null));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @Test
  void payloadAcceptsExactlyTheFiveDeclaredFields() {
    // The record's shape IS the input allowlist — Jackson drops unknown properties, so a field
    // added here is a field that can reach a log line. Stack traces, document.title, DOM content,
    // form values and location.search must stay impossible to submit.
    List<String> components =
        Arrays.stream(ClientErrorReport.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

    assertThat(components).containsExactly("message", "source", "line", "column", "kind");
  }

  @Test
  void beaconModuleShipsExactlyTheServerSideKindAllowlist() throws IOException {
    String beacon = readBeaconModule();

    // Both halves of the kind vocabulary drift silently otherwise: a client kind the server does
    // not know contributes no series at all, and the failure looks exactly like "no errors".
    assertThat(ClientErrorReportController.ALLOWED_KINDS)
        .allSatisfy(kind -> assertThat(beacon).contains("'" + kind + "'"));
    assertThat(beacon).contains("'" + ClientErrorReportController.PATH + "'");
  }

  @Test
  void beaconSendsExactlyTheFiveAllowedFieldsAndNothingElse() throws IOException {
    String beacon = readBeaconModule();
    Matcher body = BEACON_BODY.matcher(beacon);
    assertThat(body.find()).as("no JSON.stringify({…}) request body in %s", BEACON_MODULE).isTrue();

    List<String> keys = new ArrayList<>();
    Matcher key = OBJECT_KEY.matcher(body.group(1));
    while (key.find()) {
      keys.add(key.group(1));
    }

    // The client-side mirror of payloadAcceptsExactlyTheFiveDeclaredFields: the server ignores
    // extra properties, so a stack trace, document.title, DOM content, a form value or
    // location.search added to this literal would leave the browser unnoticed before the server
    // ever got the chance to drop it.
    assertThat(keys).containsExactlyInAnyOrder("kind", "message", "source", "line", "column");
  }

  private double counterFor(String kind) {
    Counter counter =
        meterRegistry.find(MetricNames.CLIENT_ERROR).tag(MetricNames.TAG_KIND, kind).counter();
    assertThat(counter).isNotNull();
    return counter.count();
  }

  private static String readBeaconModule() throws IOException {
    try (InputStream in =
        ClientErrorReportControllerTest.class.getResourceAsStream(BEACON_MODULE)) {
      assertThat(in).as("missing classpath resource %s", BEACON_MODULE).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
