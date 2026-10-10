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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a refinery-order draft from a screenshot extract without persisting it, for the exchange's
 * refinery draft (REQ-XCH-019) as for the Raffinerie import (plan §5.3).
 */
public interface RefineryDraftBuilder {

  /**
   * Validates the extract envelope and builds the best-effort draft from its first order.
   *
   * @param extract the validated {@code RefineryExtract} payload
   * @param callerId id of the uploading member; becomes the draft's owner when it resolves
   * @return the draft order plus issues and match counters
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException with an i18n key
   *     when the schema version or the panel type is not supported
   */
  @NotNull
  RefineryImportDraftDto buildDraft(@NotNull RefineryExtractDto extract, @Nullable UUID callerId);
}
