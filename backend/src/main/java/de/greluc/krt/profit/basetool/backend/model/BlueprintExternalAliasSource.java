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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * Identifies which external catalogue a {@link BlueprintExternalAlias} row maps onto. The unique
 * constraint on {@code (source_system, external_name)} keeps the alias namespace per-source,
 * leaving room for further sources should other blueprint exporters be added later.
 */
public enum BlueprintExternalAliasSource {

  /**
   * Blueprint product name as it appears in a Star Citizen {@code Game.log} {@code "Received
   * Blueprint"} line. Both the SCMDB log-watcher and the Basetool Blueprint Extractor emit these
   * names, so they share this alias namespace.
   */
  SCMDB
}
