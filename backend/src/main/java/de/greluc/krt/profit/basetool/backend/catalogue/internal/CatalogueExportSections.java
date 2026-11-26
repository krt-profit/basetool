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

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis.ART_15;

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
            .scrubbing("note"));
  }
}
