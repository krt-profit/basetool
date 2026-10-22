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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The catalogue's part of the person search: the columns of the synced and curated catalogue that
 * can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class CatalogueMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("LOCATION", "location", "description", "id", null),
        new Target("LOCATION", "location", "name", "id", null),
        new Target("MATERIAL", "material_external_alias", "created_by", "id", null),
        new Target("MATERIAL", "material_external_alias", "note", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "blueprint_dismantle_return.wiki_name_snapshot",
        "blueprint_ingredient.kind",
        "blueprint_ingredient.wiki_name_snapshot",
        "blueprint_requirement_group.group_key",
        "blueprint_requirement_group.kind",
        "blueprint_requirement_group.name",
        "blueprint_requirement_modifier.better_when",
        "blueprint_requirement_modifier.label",
        "blueprint_requirement_modifier.property_key",
        "blueprint_requirement_modifier.value_range_type",
        "blueprint_summary_property.better_when",
        "blueprint_summary_property.label",
        "blueprint_summary_property.property_key",
        "external_sync_report.aggregate",
        "external_sync_report.detail",
        "external_sync_report.event_type",
        "external_sync_report.external_name",
        "external_sync_report.source_system",
        "frequency_type.created_by",
        "frequency_type.description",
        "frequency_type.name",
        "frequency_type.updated_by",
        "job_type.archetype",
        "job_type.description",
        "job_type.name",
        "material_category.name",
        "material_external_alias.external_code",
        "material_external_alias.external_key",
        "material_external_alias.external_name",
        "material_external_alias.source_system",
        "p4k_import_job.error_message",
        "p4k_import_job.kind",
        "p4k_import_job.result_json",
        "p4k_import_job.source_filename",
        "p4k_import_job.status",
        "quality_tier.code",
        "quality_tier.label_de",
        "quality_tier.label_en");
  }

  @Override
  public @NotNull Set<String> exemptButMayHoldNames() {
    return Set.of(
        "p4k_import_job.error_message",
        "p4k_import_job.result_json",
        "p4k_import_job.source_filename");
  }

  @Override
  public @NotNull Set<String> exemptTables() {
    return Set.of(
        "blueprint",
        "city",
        "faction",
        "game_item",
        "game_item_price",
        "jurisdiction",
        "manufacturer",
        "material",
        "moon",
        "orbit",
        "outpost",
        "planet",
        "poi",
        "refining_method",
        "ship_type",
        "ship_type_uex_alias",
        "space_station",
        "star_system",
        "terminal",
        "uex_category");
  }
}
