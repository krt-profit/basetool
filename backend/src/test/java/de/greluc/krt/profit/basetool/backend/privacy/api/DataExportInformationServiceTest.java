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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Activity;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Retention;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Source;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/**
 * Unit tests for {@link DataExportInformationService}: the information is cut to the sections and
 * to the member. The message source is empty, so every text resolves to its own key.
 */
class DataExportInformationServiceTest {

  private DataExportInformationService service;

  @BeforeEach
  void setUp() {
    service = new DataExportInformationService(new DataExportLabels(new StaticMessageSource()));
  }

  private static Section section(
      int position, Activity activity, Retention retention, Source... sources) {
    return new Section(
            position,
            "section" + position,
            LegalBasis.ART_15,
            "A section of the information test.",
            "SELECT 1 FROM app_user WHERE id = :userId")
        .processedAs(
            activity, retention, sources[0], Arrays.copyOfRange(sources, 1, sources.length));
  }

  @Test
  void onlyTheActivitiesRulesAndSourcesTheSectionsUseAreDescribedInReadingOrder() {
    List<Section> sections =
        List.of(
            section(
                10, Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Source.DISCORD, Source.MEMBER),
            section(20, Activity.BANKING, Retention.BANK_RECORDS_PERMANENT, Source.OTHER_MEMBERS),
            section(30, Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Source.MEMBER));

    DataExportInformation information = service.describe(sections, false, List.of("backups"));

    assertThat(information.activities())
        .extracting(DataExportInformation.ProcessingActivity::code)
        .containsExactly("ACCOUNT", "BANKING");
    assertThat(information.activities().getFirst().purpose())
        .isEqualTo("pdf.export.activity.ACCOUNT.purpose");
    assertThat(information.retention())
        .containsOnlyKeys("ACCOUNT_LIFETIME", "BANK_RECORDS_PERMANENT")
        .containsEntry("ACCOUNT_LIFETIME", "pdf.export.retention.ACCOUNT_LIFETIME");
    assertThat(information.sources().keySet())
        .containsExactly("MEMBER", "DISCORD", "OTHER_MEMBERS");
    assertThat(information.notIncluded())
        .containsExactly(Map.entry("backups", "pdf.export.outOfScope.backups"));
  }

  @Test
  void aMemberWithoutDiscordHasNeitherTheDiscordRecipientNorATransfer() {
    DataExportInformation information =
        service.describe(
            List.of(section(10, Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Source.MEMBER)),
            false,
            List.of());

    assertThat(information.recipients())
        .containsExactly(
            "pdf.export.info.recipient.hosting",
            "pdf.export.info.recipient.mail",
            "pdf.export.info.recipient.connectedApps",
            "pdf.export.info.recipient.members",
            "pdf.export.info.recipient.authorities");
    assertThat(information.thirdCountryTransfers()).isEqualTo("pdf.export.info.thirdCountry.none");
  }

  @Test
  void aMemberWhoSignsInThroughDiscordHasTheDiscordRecipientAndTheTransfer() {
    DataExportInformation information =
        service.describe(
            List.of(section(10, Activity.ACCOUNT, Retention.ACCOUNT_LIFETIME, Source.DISCORD)),
            true,
            List.of());

    assertThat(information.recipients())
        .containsSubsequence(
            "pdf.export.info.recipient.mail",
            "pdf.export.info.recipient.discord",
            "pdf.export.info.recipient.connectedApps");
    assertThat(information.thirdCountryTransfers())
        .isEqualTo("pdf.export.info.thirdCountry.discord");
    assertThat(information.rights()).isEqualTo("pdf.export.info.rights.text");
    assertThat(information.complaint()).isEqualTo("pdf.export.info.complaint.text");
    assertThat(information.automatedDecisions())
        .isEqualTo("pdf.export.info.automatedDecisions.text");
  }

  @Test
  void aSectionWithoutAProcessingIsRefused() {
    Section undeclared =
        new Section(
            10,
            "undeclared",
            LegalBasis.ART_15,
            "A section without a processing.",
            "SELECT 1 FROM app_user WHERE id = :userId");

    assertThatThrownBy(() -> service.describe(List.of(undeclared), false, List.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("undeclared");
  }
}
