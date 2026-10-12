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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The blueprint's part of the person search: the columns of personal blueprints and the blueprint
 * aliases that can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class PersonalBlueprintMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("MATERIAL", "blueprint_external_alias", "created_by", "id", null),
        new Target("MATERIAL", "blueprint_external_alias", "note", "id", null),
        new Target("PERSONAL_INVENTORY", "personal_blueprint", "note", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "blueprint_external_alias.external_name",
        "blueprint_external_alias.product_key",
        "blueprint_external_alias.product_name",
        "blueprint_external_alias.source_system",
        "default_blueprint.created_by",
        "default_blueprint.product_key",
        "default_blueprint.product_name",
        "default_blueprint.scwiki_key",
        "personal_blueprint.product_key",
        "personal_blueprint.product_name",
        "personal_blueprint.source",
        "personal_blueprint.source_client_id");
  }
}
