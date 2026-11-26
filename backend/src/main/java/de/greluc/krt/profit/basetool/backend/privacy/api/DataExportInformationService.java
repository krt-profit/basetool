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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Activity;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Processing;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Builds the Art. 15(1) and (2) GDPR information of a data export from the processing its sections
 * declare (REQ-SEC-058, ADR-0413): only the activities, retention rules and sources the sections
 * use, and the Discord recipient and third-country transfer only for a member who signs in through
 * Discord.
 */
@Service
@RequiredArgsConstructor
public class DataExportInformationService {

  /** The key prefix of a processing activity's name, purpose and lawful basis. */
  static final String ACTIVITY = "pdf.export.activity.";

  /** The key prefix of a retention rule's text. */
  static final String RETENTION = "pdf.export.retention.";

  /** The key prefix of a source's text. */
  static final String SOURCE = "pdf.export.source.";

  /** The key prefix of the general information. */
  static final String INFO = "pdf.export.info.";

  /** The key prefix of the texts of the surfaces the export does not cover. */
  static final String NOT_INCLUDED = "pdf.export.outOfScope.";

  /** The recipients of a member's data, in the order of section 18 of the privacy policy. */
  static final List<String> RECIPIENTS =
      List.of("hosting", "mail", "discord", "connectedApps", "members", "authorities");

  /** The recipient only a member who signs in through Discord has. */
  static final String DISCORD_RECIPIENT = "discord";

  private final DataExportLabels labels;

  /**
   * Describes the processing behind an export.
   *
   * @param sections the export's sections in reading order, each with its processing
   * @param discordSignIn whether the member's account is linked to Discord
   * @param notIncluded the keys of the surfaces the export does not cover
   * @return the information, cut to the sections and the member
   * @throws NullPointerException when a section names no processing
   */
  public @NotNull DataExportInformation describe(
      @NotNull List<Section> sections, boolean discordSignIn, @NotNull List<String> notIncluded) {
    Map<Activity, DataExportInformation.ProcessingActivity> activities = new LinkedHashMap<>();
    Map<String, String> retention = new LinkedHashMap<>();
    Map<String, String> sources = new LinkedHashMap<>();
    for (Section section : sections) {
      Processing processing =
          Objects.requireNonNull(
              section.processing(), () -> "The section " + section.key() + " names no processing");
      activities.computeIfAbsent(processing.activity(), this::activity);
      retention.computeIfAbsent(
          processing.retention().name(), code -> labels.label(RETENTION + code));
      processing.sources().stream()
          .sorted()
          .map(Source::name)
          .forEach(code -> sources.computeIfAbsent(code, c -> labels.label(SOURCE + c)));
    }

    List<String> recipients = new ArrayList<>();
    for (String recipient : RECIPIENTS) {
      if (discordSignIn || !recipient.equals(DISCORD_RECIPIENT)) {
        recipients.add(labels.label(INFO + "recipient." + recipient));
      }
    }

    Map<String, String> notCovered = new LinkedHashMap<>();
    notIncluded.forEach(key -> notCovered.put(key, labels.label(NOT_INCLUDED + key)));

    return new DataExportInformation(
        List.copyOf(activities.values()),
        retention,
        sources,
        recipients,
        labels.label(INFO + "rights.text"),
        labels.label(INFO + "complaint.text"),
        labels.label(INFO + "automatedDecisions.text"),
        labels.label(INFO + "thirdCountry." + (discordSignIn ? "discord" : "none")),
        notCovered);
  }

  private DataExportInformation.ProcessingActivity activity(Activity activity) {
    String prefix = ACTIVITY + activity.name();
    return new DataExportInformation.ProcessingActivity(
        activity.name(),
        labels.label(prefix + ".name"),
        labels.label(prefix + ".purpose"),
        labels.label(prefix + ".basis"));
  }
}
