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
 * Unit tests for {@link ClientErrorReportController}, the server half of the client-error beacon.
 *
 * <p>Asserts the guardrails for an attacker-controllable payload: logging at DEBUG, a {@code kind}
 * tag from the server-side allowlist, script URLs stripped of their query string, and control
 * characters removed (CWE-117).
 */
class ClientErrorReportControllerTest {

  /** The shipped beacon module, read off the test classpath to pin the client/server kind set. */
  private static final String BEACON_MODULE = "/static/js/krt-client-error.js";

  /** Captures the beacon's {@code payload} object literal, which has no nested braces. */
  private static final Pattern BEACON_PAYLOAD = Pattern.compile("const payload = \\{([^}]*)\\}");

  /** Matches the beacon's request body being exactly the {@link #BEACON_PAYLOAD} object. */
  private static final Pattern BEACON_BODY_IS_THE_PAYLOAD =
      Pattern.compile("body: JSON\\.stringify\\(payload\\)");

  /** Matches one {@code const KIND_… = '…';} declaration in the beacon and captures the value. */
  private static final Pattern BEACON_KIND_DECLARATION =
      Pattern.compile("const KIND_[A-Z0-9_]+ = '([a-z0-9_]+)';");

  /**
   * Matches one property key inside the captured object literal, in both spellings: {@code name:
   * value} and the shorthand {@code name,} that ESLint's {@code object-shorthand} rule requires
   * since FE-MOD-03. Prettier puts every property of the multi-line literal on its own line, so the
   * key is the identifier a line starts with.
   */
  private static final Pattern OBJECT_KEY = Pattern.compile("(?m)^\\s*(\\w+)\\s*(?::|,|$)");

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

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
  }

  @ParameterizedTest
  @ValueSource(strings = {"stack_trace", "SCRIPT_ERROR", "", "script_error ", "../../etc"})
  void unknownKind_isRejectedAndCreatesNoMeterSeries(String kind) {
    ResponseEntity<Void> response =
        controller.report(new ClientErrorReport("boom", "https://app/js/a.js", 1, 2, kind));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
    assertThat(line).doesNotContain("\n");
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
    List<String> components =
        Arrays.stream(ClientErrorReport.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

    assertThat(components).containsExactly("message", "source", "line", "column", "kind");
  }

  @Test
  void beaconModuleShipsExactlyTheServerSideKindAllowlist() throws IOException {
    String beacon = readBeaconModule();

    assertThat(ClientErrorReportController.ALLOWED_KINDS)
        .allSatisfy(kind -> assertThat(beacon).contains("'" + kind + "'"));
    assertThat(beacon).contains("'" + ClientErrorReportController.PATH + "'");
  }

  @Test
  void beaconSendsExactlyTheFiveAllowedFieldsAndNothingElse() throws IOException {
    String beacon = readBeaconModule();
    Matcher payload = BEACON_PAYLOAD.matcher(beacon);
    assertThat(payload.find()).as("no `const payload = {…}` literal in %s", BEACON_MODULE).isTrue();
    assertThat(BEACON_BODY_IS_THE_PAYLOAD.matcher(beacon).find())
        .as("%s does not send the payload object verbatim as the request body", BEACON_MODULE)
        .isTrue();

    List<String> keys = new ArrayList<>();
    Matcher key = OBJECT_KEY.matcher(payload.group(1));
    while (key.find()) {
      keys.add(key.group(1));
    }

    assertThat(keys).containsExactlyInAnyOrder("kind", "message", "source", "line", "column");
  }

  @Test
  void everyKindTheBeaconDeclaresIsOneTheServerAccepts() throws IOException {
    String beacon = readBeaconModule();
    Matcher declaration = BEACON_KIND_DECLARATION.matcher(beacon);
    List<String> beaconKinds = new ArrayList<>();
    while (declaration.find()) {
      beaconKinds.add(declaration.group(1));
    }

    assertThat(beaconKinds)
        .containsExactlyInAnyOrderElementsOf(ClientErrorReportController.ALLOWED_KINDS);
    assertThat(beaconKinds).contains(MetricNames.CLIENT_ERROR_CSP_VIOLATION);
  }

  @Test
  void beaconListensForCspViolations() throws IOException {
    assertThat(readBeaconModule()).contains("addEventListener('securitypolicyviolation'");
  }

  @Test
  void cspViolationReport_isCountedUnderItsOwnKind() {
    ResponseEntity<Void> response =
        controller.report(
            new ClientErrorReport(
                "script-src-elem",
                "https://evil.example",
                null,
                null,
                MetricNames.CLIENT_ERROR_CSP_VIOLATION));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(counterFor(MetricNames.CLIENT_ERROR_CSP_VIOLATION)).isEqualTo(1.0d);
  }

  @Test
  void i18nMissingReport_isCountedUnderItsOwnKind() {
    ResponseEntity<Void> response =
        controller.report(
            new ClientErrorReport(
                "herkunftI18n.rest", null, null, null, MetricNames.CLIENT_ERROR_I18N_MISSING));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(counterFor(MetricNames.CLIENT_ERROR_I18N_MISSING)).isEqualTo(1.0d);
  }

  @Test
  void beaconExposesTheMissingTranslationCheck() throws IOException {
    String beacon = readBeaconModule();
    assertThat(beacon).contains("window.krtI18nText = i18nText;");
    assertThat(beacon).contains("report(KIND_I18N_MISSING, name, null, null, null);");
  }

  @Test
  void cspViolationSource_isReducedToItsOriginServerSide() {
    controller.report(
        new ClientErrorReport(
            "img-src",
            "https://user:secret@evil.example:8443/track/member-42?token=abc#frag",
            null,
            null,
            MetricNames.CLIENT_ERROR_CSP_VIOLATION));

    String line = appender.list.getFirst().getFormattedMessage();
    assertThat(line).contains("source=https://evil.example:8443,");
    assertThat(line).doesNotContain("member-42").doesNotContain("token").doesNotContain("secret");
  }

  @Test
  void originOnly_keepsTheBrowserKeywordsAndSchemes() {
    assertThat(ClientErrorReportController.originOnly("inline")).isEqualTo("inline");
    assertThat(ClientErrorReportController.originOnly("eval")).isEqualTo("eval");
    assertThat(ClientErrorReportController.originOnly("data:image/png;base64,AAAA"))
        .isEqualTo("data");
    assertThat(ClientErrorReportController.originOnly("https://cdn.example/a.js?x=1"))
        .isEqualTo("https://cdn.example");
    assertThat(ClientErrorReportController.originOnly(null)).isNull();
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
