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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Bank export directory misconfiguration fails validation during configuration binding. */
class BankExportPropertiesTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void relativeExportDirectoryFailsStartup() {
    context
        .withPropertyValues("app.bank.exports.directory=relative")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  void defaultUsesTheExplicitJvmTemporaryDirectory() {
    context.run(application ->
        assertThat(application.getBean(BankExportProperties.class).directory())
            .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"))));
  }

  /** Isolates validated property binding from the application runtime. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BankExportProperties.class)
  static class PropertiesConfiguration {}
}
