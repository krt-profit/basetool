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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionObjectiveKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable planning content in editor order.
 *
 * <p>Every part is optional: a {@code null} value or an empty list is a part the template leaves to
 * the planner (REQ-MISSION-035).
 *
 * @param description the optional Markdown description
 * @param meetingPoint the optional meeting point
 * @param isInternal the optional visibility, {@code null} when the template leaves it open
 * @param meetingTime the optional TeamSpeak meeting time of day
 * @param plannedStartTime the optional planned start time of day
 * @param plannedEndTime the optional planned end time of day
 * @param frequencies mission-wide radio channels
 * @param objectives classified goals
 * @param steps procedure steps
 * @param units planned units
 */
public record MissionTemplatePlan(
    @Nullable String description,
    @Nullable String meetingPoint,
    @Nullable Boolean isInternal,
    @Nullable LocalTime meetingTime,
    @Nullable LocalTime plannedStartTime,
    @Nullable LocalTime plannedEndTime,
    List<@NotNull @Valid Frequency> frequencies,
    List<@NotNull @Valid Objective> objectives,
    List<@NotNull @Valid Step> steps,
    List<@NotNull @Valid Unit> units) {

  /**
   * Mission-wide radio channel bound to exactly one of a catalogue frequency type or a custom
   * label.
   *
   * @param frequencyTypeId the catalogue frequency type, or {@code null} for a custom channel
   * @param name the custom channel label, or {@code null} for a typed channel
   * @param value the frequency value with at most two decimals
   */
  @Embeddable
  @Schema(name = "MissionTemplateFrequency")
  public record Frequency(
      @Nullable UUID frequencyTypeId,
      @Nullable @Size(max = 100) @Column(length = 100) String name,
      @NotNull
          @DecimalMin("0")
          @DecimalMax("999.99")
          @Digits(integer = 3, fraction = 2)
          @Column(name = "frequency_value", nullable = false, precision = 5, scale = 2)
          BigDecimal value) {}

  /**
   * Classified goal.
   *
   * @param title the goal text
   * @param kind the goal classification
   */
  @Embeddable
  @Schema(name = "MissionTemplateObjective")
  public record Objective(
      @NotBlank @Size(max = 500) @Column(nullable = false, length = 500) String title,
      @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
          MissionObjectiveKind kind) {}

  /**
   * Procedure step with an optional time or place hint.
   *
   * @param title the step text
   * @param meta the optional hint
   */
  @Embeddable
  @Schema(name = "MissionTemplateStep")
  public record Step(
      @NotBlank @Size(max = 500) @Column(nullable = false, length = 500) String title,
      @Nullable @Size(max = 200) @Column(length = 200) String meta) {}

  /**
   * Planned unit.
   *
   * @param name the display name
   * @param shipTypeId the optional catalogue ship type
   * @param note the optional planning note
   * @param highValueUnit whether this is a high-value unit
   * @param frequency the optional comms frequency
   */
  @Embeddable
  @Schema(name = "MissionTemplateUnit")
  public record Unit(
      @NotBlank @Size(max = 255) @Column(nullable = false) String name,
      @Nullable UUID shipTypeId,
      @Nullable @Size(max = 500) @Column(length = 500) String note,
      @Column(nullable = false) boolean highValueUnit,
      @Nullable Double frequency) {}
}
