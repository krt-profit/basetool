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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The blueprint import's name resolution, for other modules (plan §5.3). Nothing is persisted. */
public interface BlueprintImports {

  /**
   * Previews how each entry resolves for the owner.
   *
   * @param ownerUserId the owner the preview is for
   * @param parsed the de-duplicated entries
   * @return the preview with per-name rows and per-status counts
   */
  @NotNull
  BlueprintImportPreviewDto previewEntries(
      @NotNull UUID ownerUserId, @NotNull List<ParsedEntry> parsed);

  /**
   * Resolves bare product names, one result per name in order.
   *
   * @param names the names
   * @return one resolution per name, never {@code null}
   */
  @NotNull
  List<NameResolution> resolveNames(@NotNull List<String> names);
}
