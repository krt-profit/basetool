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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Right.ART_15_20;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The refinery's part of the Art. 15 / Art. 20 data export: the member's refinery orders with their
 * provenance and pickup marker, and their ores and yields (REQ-SEC-058, REQ-REFINERY-027).
 */
@Component
public class RefineryExportSections implements PersonalDataExportSection {

  @Override
  public @NotNull List<Section> sections() {
    return List.of(
        new Section(
                280,
                "refineryOrdersOwned",
                ART_15_20,
                "Created by the member.",
                """
                SELECT r.status, r.started_at, r.duration_minutes, rm.name AS refining_method,
                       r.expenses, r.other_expenses, r.ore_sales, l.name AS location,
                       o.name AS org_unit, m.name AS mission, r.stored_at, r.ready_notified_at,
                       r.canceled_by_patch_change IS NOT NULL AS canceled_by_patch_change,
                       r.created_at, r.updated_at, r.created_source, r.created_client,
                       r.last_change_source, r.last_change_client, r.last_changed_at,
                       r.picked_up_at, r.picked_up_client
                FROM refinery_order r
                  LEFT JOIN location l ON l.id = r.location_id
                  LEFT JOIN refining_method rm ON rm.id = r.refining_method_id
                  LEFT JOIN org_unit o ON o.id = r.owning_org_unit_id
                  LEFT JOIN mission m ON m.id = r.mission_id
                WHERE r.owner_id = :userId ORDER BY r.created_at
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.REASSIGNED_ON_DELETION,
                Source.MEMBER,
                Source.CONNECTED_APPLICATION)
            .scrubbing("mission")
            .keeping("location", "location.name; see warehouseContributions.location.")
            .keeping(
                "refining_method",
                "refining_method.name: a method from the synced catalogue; see"
                    + " warehouseContributions.material.")
            .keeping("org_unit", "org_unit.name; see orgUnitMemberships.org_unit."),
        new Section(
                285,
                "refineryOrderGoods",
                ART_15_20,
                "The ores and yields of the member's refinery orders, entered by the member or"
                    + " their connected application.",
                """
                SELECT r.started_at AS order_started_at, i.name AS input_material,
                       g.input_quantity, g.quality, o.name AS output_material, g.output_quantity,
                       g.created_at, g.updated_at
                FROM refinery_good g
                  JOIN refinery_order r ON r.id = g.refinery_order_id
                  LEFT JOIN material i ON i.id = g.input_material_id
                  LEFT JOIN material o ON o.id = g.output_material_id
                WHERE r.owner_id = :userId ORDER BY r.created_at, g.created_at, g.id
                """)
            .processedAs(
                Activity.OPERATIONS,
                Retention.REASSIGNED_ON_DELETION,
                Source.MEMBER,
                Source.CONNECTED_APPLICATION));
  }
}
