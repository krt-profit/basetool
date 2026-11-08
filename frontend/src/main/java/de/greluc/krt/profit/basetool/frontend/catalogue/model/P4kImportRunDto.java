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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * Frontend mirror of the backend {@code P4kImportRunDto}: one applied P4K run of the admin run list
 * (REQ-P4K-015). Ids, enums and timestamps are carried as strings.
 *
 * @param id the run id
 * @param appliedAt ISO-8601 instant of the APPLY
 * @param channel the Channel, always {@code LIVE}
 * @param buildBranch the Build branch
 * @param buildVersion the Build version
 * @param changeNumber the change number
 * @param patchVersion the patch version, or {@code null} when unknown
 * @param patchVersionOrigin {@code PUBLIC_PATCH}, {@code BRANCH_LOWER_BOUND} or {@code null}
 * @param createdBy the uploading admin's user id
 * @param kinds the counts per kind
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record P4kImportRunDto(
    String id,
    String appliedAt,
    String channel,
    String buildBranch,
    String buildVersion,
    long changeNumber,
    String patchVersion,
    @BackendEnumAsString String patchVersionOrigin,
    String createdBy,
    List<Kind> kinds) {

  /**
   * The counts of one kind in one run.
   *
   * @param kind the kind
   * @param completeness {@code COMPLETE} or {@code PARTIAL}
   * @param matched records matched to a row
   * @param unmatched records without a row
   * @param added rows created
   * @param changed matched rows the run wrote
   * @param vanished rows the sweep stamped
   * @param reappeared stamped rows matched again
   * @param sweep what the sweep did, or {@code null} on a run recorded before the sweep existed
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Kind(
      @BackendEnumAsString String kind,
      @BackendEnumAsString String completeness,
      long matched,
      long unmatched,
      long added,
      long changed,
      long vanished,
      long reappeared,
      @BackendEnumAsString String sweep) {}
}
