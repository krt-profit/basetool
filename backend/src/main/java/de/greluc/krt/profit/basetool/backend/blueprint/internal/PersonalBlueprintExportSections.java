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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15;
import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The blueprint's part of the Art. 15 / Art. 20 data export: the member's personal blueprints, the
 * import names their imports taught and their sharing choice (REQ-SEC-058).
 */
@Component
public class PersonalBlueprintExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                190,
                "personalBlueprints",
                ART_15_20,
                "Entered by the member.",
                """
                SELECT product_name, acquired_at, note, source, source_client_id, created_at,
                       updated_at
                FROM personal_blueprint WHERE owner_user_id = :userId ORDER BY created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.ACCOUNT_LIFETIME,
                Source.MEMBER,
                Source.CONNECTED_APPLICATION)
            .scrubbing("note"),
        new Section(
                195,
                "blueprintImportNames",
                ART_15,
                "Learned when one of the member's blueprint imports named a blueprint by a foreign"
                    + " name; the name serves every later import and records the account that"
                    + " taught it.",
                """
                SELECT source_system, external_name, product_key, product_name, note, created_at,
                       updated_at
                FROM blueprint_external_alias WHERE created_by = CAST(:userId AS VARCHAR)
                ORDER BY created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.UNLINKED_ON_DELETION, Source.MEMBER)
            .scrubbing("note"),
        new Section(
                470,
                "blueprintSharing",
                ART_15_20,
                "Chosen by the member on the profile page.",
                """
                SELECT share_globally, created_at, updated_at
                FROM member_blueprint_sharing WHERE user_id = :userId
                """)
            .processedAs(Activity.OPERATIONS, Retention.ACCOUNT_LIFETIME, Source.MEMBER),
        new Section(
                690,
                "defaultBlueprintsAdded",
                ART_15,
                "Default blueprints the member added to the organisation's list as an"
                    + " administrator, kept with the account id that added them.",
                """
                SELECT product_name, created_at
                FROM default_blueprint WHERE created_by = CAST(:userId AS VARCHAR)
                ORDER BY created_at
                """)
            .processedAs(Activity.OPERATIONS, Retention.ADMIN_ACT_PERMANENT, Source.MEMBER));
  }
}
