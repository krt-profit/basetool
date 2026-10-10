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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.testsupport.logging.CredentialFieldRule;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Ratchet (REQ-OBS-004): every type of this module that holds a credential-named field is on the
 * reviewed list of redacting types.
 */
class CredentialFieldRatchetTest {

  private static final Set<String> REVIEWED_REDACTING_TYPES =
      Set.of(
          "de.greluc.krt.profit.basetool.backend.config.DiscordSpiPrecheckProperties",
          "de.greluc.krt.profit.basetool.backend.identity.api.KeycloakSyncProperties",
          "de.greluc.krt.profit.basetool.backend.config.MonitoringScrapeProperties");

  @Test
  void everyCredentialBearingTypeIsReviewedAndRedacts() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.greluc.krt.profit.basetool.backend");

    assertThat(CredentialFieldRule.violations(classes, REVIEWED_REDACTING_TYPES)).isEmpty();
  }
}
