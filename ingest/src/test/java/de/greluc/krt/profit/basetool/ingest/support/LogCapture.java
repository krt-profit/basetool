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

package de.greluc.krt.profit.basetool.ingest.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Captures the logback events one class emits while a block runs, so tests can assert log levels
 * (REQ-OBS-001). The temporary appender and level are always restored.
 */
public final class LogCapture {

  private LogCapture() {}

  /** A block of test code that may throw a checked exception. */
  @FunctionalInterface
  public interface ThrowingRunnable {

    /**
     * Runs the block.
     *
     * @throws Exception whatever the block under test propagates
     */
    void run() throws Exception;
  }

  /**
   * Runs {@code block} with a list appender attached to {@code source}'s logger and returns the
   * events it emitted.
   *
   * @param source the class whose logger is captured
   * @param level the level the logger is temporarily set to, so events at that level are not
   *     filtered out before reaching the appender
   * @param block the code to run while capturing
   * @return the captured events, in emission order
   * @throws IllegalStateException wrapping any checked exception the block throws
   */
  public static List<ILoggingEvent> capture(Class<?> source, Level level, ThrowingRunnable block) {
    Logger logger = (Logger) LoggerFactory.getLogger(source);
    Level original = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(level);
    try {
      block.run();
    } catch (Exception e) {
      throw new IllegalStateException("The captured block failed unexpectedly", e);
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
      appender.stop();
    }
    return List.copyOf(appender.list);
  }
}
