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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * Thrown when generating a downloadable report (PDF, CSV, …) fails in the report pipeline.
 *
 * <p>Mapped to {@code 500} with code {@code REPORT_GENERATION_FAILED}. Its {@link
 * #disclosurePolicy()} is {@link ErrorDisclosurePolicy#SUPPRESSED}: the cause is logged and the
 * client receives a generic localized detail.
 */
public final class ReportGenerationException extends AppException {

  /**
   * Creates a {@code ReportGenerationException} with a description of the report-pipeline failure.
   *
   * @param message human-readable summary of the report failure for server logging
   */
  public ReportGenerationException(String message) {
    super(AppExceptionKind.REPORT_GENERATION_FAILED, message);
  }

  /**
   * Creates the exception wrapping the original library or I/O failure, which is kept for the
   * server log.
   *
   * @param message summary of the report failure for the server log
   * @param cause underlying library or I/O failure
   */
  public ReportGenerationException(String message, Throwable cause) {
    super(AppExceptionKind.REPORT_GENERATION_FAILED, message, cause);
  }
}
