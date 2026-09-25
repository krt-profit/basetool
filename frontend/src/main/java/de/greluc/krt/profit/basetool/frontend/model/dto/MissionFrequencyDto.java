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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One radio frequency of a mission: exactly one of {@code frequencyType} (a global type) or {@code
 * name} (a custom label) is set (REQ-MISSION-014).
 *
 * @param id the frequency row id.
 * @param frequencyType the referenced global frequency type, or {@code null} for a custom channel.
 * @param name the custom channel label, or {@code null} for a typed channel.
 * @param value the frequency value.
 * @param version the optimistic-lock version echoed back on edits.
 */
public record MissionFrequencyDto(
    UUID id, FrequencyTypeRef frequencyType, String name, BigDecimal value, Long version) {
  /** Immutable record carrying Frequency Type Ref data. */
  public record FrequencyTypeRef(UUID id, String name) {}

  /** Convenience accessor returning the nested {@code frequencyType.id()}, or {@code null}. */
  @Nullable
  public UUID frequencyTypeId() {
    return frequencyType != null ? frequencyType.id() : null;
  }

  /**
   * Whether this row is a custom (mission-specific) channel rather than a global typed one.
   *
   * @return {@code true} when no global {@code frequencyType} is bound.
   */
  public boolean isCustom() {
    return frequencyType == null;
  }
}
