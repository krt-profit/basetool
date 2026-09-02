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

package de.greluc.krt.profit.basetool.backend.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins what the {@code io.opentelemetry.exporter} log-level pin actually does — which is not what
 * REQ-OBS-013 claimed it did until 2026-09-02.
 *
 * <p>The requirement asserted that pinning the logger to WARN made the OTLP exporter "keep logging
 * export failures at WARN (not ERROR)". A logback level is a <em>threshold</em>, not a rewrite: the
 * exporter logs a transport failure at JUL {@code SEVERE}, {@code jul-to-slf4j} delivers it as
 * ERROR, and ERROR clears a WARN threshold untouched. Production proved it — {@code
 * 2026-09-02T07:24:04Z ERROR io.opentelemetry.exporter.internal.http.HttpExporter: Failed to export
 * spans} — an ERROR line from the very logger the pin covers.
 *
 * <p>The behaviour was left alone and the requirement corrected, because it is acceptable on its
 * own terms: the SDK's {@code ThrottlingLogger} caps that source at one message per minute, two
 * orders below {@code LogbackErrorSpike}'s {@code > 0.2/s}, and an unreachable Alloy pages through
 * {@code TargetDown} regardless.
 *
 * <p>The third assertion is the one that matters. It fails if someone quietly sets the logger to
 * {@code OFF} — which would satisfy the requirement's original wording literally, and would also
 * remove the only local breadcrumb for "why is this window's trace missing", since Micrometer's
 * {@code MetricsTurboFilter} gates on the effective level and would stop counting the event too.
 */
class OtelExporterLogLevelTest {

  /** The logger REQ-OBS-013's pin names, and the one production logged the ERROR from. */
  private static final String EXPORTER_LOGGER = "io.opentelemetry.exporter";

  /** The level configured before this test ran, restored afterwards. */
  private Level originalLevel;

  /** The logger under test, resolved from the running logback context. */
  private Logger exporterLogger;

  @BeforeEach
  void captureAndPin() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    exporterLogger = context.getLogger(EXPORTER_LOGGER);
    originalLevel = exporterLogger.getLevel();
    // The test profile does not load the production application.yml's logging levels, so the pin is
    // applied here explicitly. What is under test is the SEMANTICS of the pin — that WARN is a
    // threshold and not a demotion — not whether a particular YAML file was read.
    exporterLogger.setLevel(Level.WARN);
  }

  @AfterEach
  void restore() {
    exporterLogger.setLevel(originalLevel);
  }

  @Test
  void theWarnPinSuppressesInfoChatter() {
    // The pin's real, residual effect, and the reason it is not dead configuration to be deleted.
    assertFalse(exporterLogger.isInfoEnabled());
  }

  @Test
  void theWarnPinDoesNotSuppressError() {
    // The corrected claim. A threshold of WARN passes ERROR through unchanged — which is why the
    // 07:24:04Z production line exists at all, and why REQ-OBS-013 no longer says otherwise.
    assertTrue(exporterLogger.isErrorEnabled());
  }

  @Test
  void warnItselfStillPasses() {
    assertTrue(exporterLogger.isWarnEnabled());
  }
}
