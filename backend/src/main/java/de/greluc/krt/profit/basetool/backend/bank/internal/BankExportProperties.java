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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Filesystem placement of private, request-lived Bank report snapshots.
 *
 * @param directory the parent directory for private export directories
 */
@Validated
@ConfigurationProperties("app.bank.exports")
public record BankExportProperties(@NotNull Path directory) {
  /** Defaults export storage to the JVM's configured temporary directory. */
  public BankExportProperties {
    if (directory == null) {
      directory = Path.of(System.getProperty("java.io.tmpdir"));
    }
  }

  /** Returns whether the configured directory is an absolute filesystem path. */
  @AssertTrue(message = "Bank export directory must be absolute")
  public boolean isAbsoluteDirectory() {
    return directory.isAbsolute();
  }
}
