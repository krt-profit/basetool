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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Resolves the German texts of the material and item handover-report PDFs from the backend message
 * bundle ({@code pdf.handover.*}), including the title and the header table both reports share.
 */
@Component
@RequiredArgsConstructor
class HandoverReportLabels {

  /** The language both handover reports are written in. */
  private static final Locale REPORT_LOCALE = Locale.GERMAN;

  /** The backend message bundle the texts are resolved from. */
  private final MessageSource messageSource;

  /**
   * Resolves the title both handover reports carry.
   *
   * @return the uppercase report title
   */
  @NotNull
  String title() {
    return resolve("pdf.handover.title");
  }

  /**
   * Builds the header table both handover reports open with: order number, handover date, handover
   * time marked as local time, and the recipient handle, shown as the anonymised placeholder once
   * erased (REQ-SEC-062).
   *
   * @param jobOrderNumber the order number including its leading {@code #}
   * @param handoverDate the formatted handover date
   * @param handoverTime the formatted handover time in the reader's zone
   * @param recipientHandle the stored recipient handle, possibly the erasure sentinel
   * @return the four labelled values in reading order
   */
  @NotNull
  List<PdfKeyValue> header(
      @NotNull String jobOrderNumber,
      @NotNull String handoverDate,
      @NotNull String handoverTime,
      @NotNull String recipientHandle) {
    return List.of(
        new PdfKeyValue(resolve("pdf.handover.jobOrderNumber"), jobOrderNumber),
        new PdfKeyValue(resolve("pdf.handover.date"), handoverDate),
        new PdfKeyValue(
            resolve("pdf.handover.time"),
            messageSource.getMessage(
                "pdf.handover.localTime", new Object[] {handoverTime}, REPORT_LOCALE)),
        new PdfKeyValue(
            resolve("pdf.handover.recipient"),
            HandleAnonymisation.humaniseRequired(
                recipientHandle, resolve("general.anonymisedHandle"))));
  }

  /**
   * Resolves one German handover-report text without arguments.
   *
   * @param key the message key
   * @return the resolved text
   * @throws org.springframework.context.NoSuchMessageException when the bundle lacks the key
   */
  @NotNull
  String resolve(@NotNull String key) {
    return messageSource.getMessage(key, null, REPORT_LOCALE);
  }
}
