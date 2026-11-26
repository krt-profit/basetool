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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * The information of Art. 15(1)(a) to (h) and (2) GDPR that accompanies a data export, cut to the
 * member: only the processing activities, retention rules and sources their sections use
 * (REQ-SEC-058).
 *
 * @param activities the processing activities of the export's sections, in reading order, with
 *     purpose and lawful basis
 * @param retention each retention rule the sections use, code to text
 * @param sources each source the sections name, code to text
 * @param recipients the recipients and categories of recipients of the member's data
 * @param rights the rights of Art. 16 to 18, 20 and 21 and how to exercise them
 * @param complaint the right to lodge a complaint and the competent supervisory authority
 * @param automatedDecisions the statement on automated decision-making of Art. 22
 * @param thirdCountryTransfers the transfers to a third country and their safeguards
 * @param notIncluded each surface the export does not cover, key to text
 */
public record DataExportInformation(
    @NotNull @Unmodifiable List<ProcessingActivity> activities,
    @NotNull @UnmodifiableView Map<String, String> retention,
    @NotNull @UnmodifiableView Map<String, String> sources,
    @NotNull @Unmodifiable List<String> recipients,
    @NotNull String rights,
    @NotNull String complaint,
    @NotNull String automatedDecisions,
    @NotNull String thirdCountryTransfers,
    @NotNull @UnmodifiableView Map<String, String> notIncluded) {

  /** Copies the lists and wraps ordered copies of the maps, so the information stays fixed. */
  public DataExportInformation {
    activities = List.copyOf(activities);
    retention = Collections.unmodifiableMap(new LinkedHashMap<>(retention));
    sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    recipients = List.copyOf(recipients);
    notIncluded = Collections.unmodifiableMap(new LinkedHashMap<>(notIncluded));
  }

  /**
   * One processing activity of the export.
   *
   * @param code the activity's code, as the sections name it
   * @param name the activity's name
   * @param purpose the purpose of the processing
   * @param lawfulBasis the lawful basis of the processing under Art. 6 GDPR
   */
  public record ProcessingActivity(
      @NotNull String code,
      @NotNull String name,
      @NotNull String purpose,
      @NotNull String lawfulBasis) {}
}
