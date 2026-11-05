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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Runs P4K APPLYs and previews in the integration tests the way the job runner does: from a stored
 * (compressed) payload, under an APPLY job row.
 *
 * @param service the import service
 * @param jobRepository the job repository, for the APPLY job each run references
 */
record P4kImportRuns(
    @NotNull P4kImportService service, @NotNull P4kImportJobRepository jobRepository) {

  /** The administrator every test run is uploaded by. */
  static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a4d1");

  /**
   * Applies a catalogue.
   *
   * @param plain the plain catalogue JSON
   * @param createItemsAndShips the run's checkbox
   * @return the result
   */
  @NotNull
  P4kImportResultDto apply(byte @NotNull [] plain, boolean createItemsAndShips) {
    P4kImportJob job = new P4kImportJob();
    job.setKind(P4kImportJobKind.APPLY);
    job.setStatus(P4kImportJobStatus.RUNNING);
    job.setCreatedBy(ADMIN);
    jobRepository.save(job);
    return service.applyImport(
        P4kCompression.toStoredPayload(plain),
        new P4kImportService.P4kApplyRequest(job.getId(), ADMIN, createItemsAndShips));
  }

  /**
   * Previews a catalogue.
   *
   * @param plain the plain catalogue JSON
   * @return the result
   */
  @NotNull
  P4kImportResultDto preview(byte @NotNull [] plain) {
    return service.previewImport(P4kCompression.toStoredPayload(plain));
  }
}
