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

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@link P4kImportJobPayload} upload side table. */
public interface P4kImportJobPayloadRepository extends JpaRepository<P4kImportJobPayload, UUID> {

  /**
   * Copies the stored upload of {@code sourceJobId} onto {@code targetJobId} entirely inside the
   * database ({@code INSERT … SELECT}), so launching an APPLY run from a PREVIEW never pulls the
   * multi-MB catalog through the application heap.
   *
   * @param targetJobId the new job that should own a copy of the payload
   * @param sourceJobId the job whose payload is copied
   * @return the number of rows inserted (1 when the source payload existed, else 0)
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO p4k_import_job_payload (job_id, content)
          SELECT :targetJobId, content FROM p4k_import_job_payload
          WHERE job_id = :sourceJobId
          """,
      nativeQuery = true)
  int copyPayload(@Param("targetJobId") UUID targetJobId, @Param("sourceJobId") UUID sourceJobId);
}
