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
 * Pins what the {@code io.opentelemetry.exporter} WARN level does: exporter failures still log at
 * ERROR, and the logger is not set to {@code OFF} (REQ-OBS-013).
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
    exporterLogger.setLevel(Level.WARN);
  }

  @AfterEach
  void restore() {
    exporterLogger.setLevel(originalLevel);
  }

  @Test
  void theWarnPinSuppressesInfoChatter() {
    assertFalse(exporterLogger.isInfoEnabled());
  }

  @Test
  void theWarnPinDoesNotSuppressError() {
    assertTrue(exporterLogger.isErrorEnabled());
  }

  @Test
  void warnItselfStillPasses() {
    assertTrue(exporterLogger.isWarnEnabled());
  }
}
