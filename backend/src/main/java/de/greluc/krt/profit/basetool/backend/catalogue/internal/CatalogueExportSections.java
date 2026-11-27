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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The catalogue's part of the Art. 15 / Art. 20 data export: the material aliases the member's
 * account created (REQ-SEC-058).
 */
@Component
public class CatalogueExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                196,
                "materialImportNames",
                ART_15,
                "A foreign name of a material the member's account added to the catalogue; the"
                    + " alias serves every later import and records the account that created it.",
                """
                SELECT m.name AS material, a.source_system, a.external_name, a.external_key,
                       a.external_uuid, a.external_code, a.note, a.created_at, a.updated_at
                FROM material_external_alias a JOIN material m ON m.id = a.material_id
                WHERE a.created_by = CAST(:userId AS VARCHAR)
                ORDER BY a.created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .scrubbing("note"),
        new Section(
                670,
                "catalogueImportsStarted",
                ART_15,
                "Catalogue import jobs the member started as an administrator; the file name and"
                    + " the result are not included, as they can name somebody.",
                """
                SELECT kind, status, file_size_bytes, create_items_and_ships, override_mass_guard,
                       started_at, finished_at, created_at
                FROM p4k_import_job WHERE created_by = :userId ORDER BY created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.CATALOGUE_IMPORT_JOB, Source.MEMBER),
        new Section(
                671,
                "catalogueImportsApplied",
                ART_15,
                "Catalogue imports the member applied as an administrator, kept as evidence of an"
                    + " act on the organisation's catalogue.",
                """
                SELECT channel, build_branch, build_version, patch_version, tool_version,
                       exported_at, applied_at
                FROM p4k_import_run WHERE created_by = :userId ORDER BY applied_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ADMIN_ACT_PERMANENT, Source.MEMBER),
        new Section(
                672,
                "catalogueReviewsResolved",
                ART_15,
                "Catalogue review cases the member resolved or queued a ship repair for as an"
                    + " administrator, kept as evidence of an act on the organisation's catalogue.",
                """
                SELECT kind, reason, resolution, status, resolved_at,
                       resolved_by IS NOT DISTINCT FROM :userId AS resolved_by_member,
                       ship_repair_by IS NOT DISTINCT FROM :userId AS repair_queued_by_member,
                       created_at
                FROM p4k_review_case
                WHERE resolved_by = :userId OR ship_repair_by = :userId ORDER BY created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ADMIN_ACT_PERMANENT, Source.MEMBER));
  }
}
