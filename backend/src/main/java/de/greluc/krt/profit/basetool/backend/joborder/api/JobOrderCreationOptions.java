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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import org.jetbrains.annotations.NotNull;

/**
 * Projects the current creation choices without enumerating pairs, for the writer rule the caller
 * passes (REQ-ORDERS-053, ADR-0408).
 */
public interface JobOrderCreationOptions {

  /**
   * Checks for an active writer unit and an eligible responsible unit under the web's {@link
   * JobOrderWriterRule#CONTEXTUAL} rule, without listing the choices.
   *
   * @return whether at least one currently valid pair can be commissioned in the web
   */
  boolean canCreate();

  /**
   * Lists the currently usable choices and one valid pair under the rule; the create command
   * rechecks the selected pair.
   *
   * @param rule the authority that decides the writer units
   * @param actor who would create; {@link JobOrderWriterRule#DIRECT_LOGISTICIAN} reads its member
   * @return a fail-closed answer when no eligible responsible unit or active writer unit exists
   */
  @NotNull
  JobOrderCreateOptionsDto current(@NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor);
}
