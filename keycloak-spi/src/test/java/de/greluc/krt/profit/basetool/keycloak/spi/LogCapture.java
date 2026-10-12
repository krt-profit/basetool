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

package de.greluc.krt.profit.basetool.keycloak.spi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Collects what a class logs while a test runs and keeps it off the console, so a path that is
 * expected to log a warning or an error can assert it instead of printing it into the build log.
 */
public final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final boolean parentHandlers;
  private final Handler handler;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();

  private LogCapture(Class<?> source) {
    logger = org.jboss.logmanager.Logger.getLogger(source.getName());
    parentHandlers = logger.getUseParentHandlers();
    handler =
        new Handler() {
          @Override
          public void publish(LogRecord entry) {
            records.add(entry);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    logger.setUseParentHandlers(false);
  }

  /**
   * Starts capturing the log of one class.
   *
   * @param source the class whose logger is captured
   * @return the capture; close it to restore the logger
   */
  public static LogCapture of(Class<?> source) {
    return new LogCapture(source);
  }

  /**
   * Tells whether a record of the given severity was logged.
   *
   * @param level the severity to look for; matched by value, so JBoss' {@code WARN} equals {@link
   *     Level#WARNING} and {@code ERROR} equals {@link Level#SEVERE}
   * @return {@code true} if at least one such record was captured
   */
  public boolean logged(Level level) {
    return records.stream().anyMatch(entry -> entry.getLevel().intValue() == level.intValue());
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setUseParentHandlers(parentHandlers);
  }
}
