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

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** An org unit's reusable planning defaults, edited as a single versioned aggregate. */
@TenantScoped("owningOrgUnit")
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MissionTemplate extends AbstractEntity<UUID> {

  /** Stable template identity. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Nullable
  private UUID id;

  /** The immutable owning org unit. */
  @Getter
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = false, updatable = false)
  private OrgUnit owningOrgUnit;

  /** The display name within the owner. */
  @Getter
  @Column(nullable = false)
  private String name;

  /** The optional Markdown description. */
  @Getter
  @Column(columnDefinition = "TEXT")
  @Nullable
  private String description;

  /** The optional meeting point. */
  @Getter
  @Column(length = 200)
  @Nullable
  private String meetingPoint;

  /** The optional visibility; {@code null} leaves it to the planner. */
  @Getter
  @Column(name = "is_internal")
  @Nullable
  private Boolean isInternal;

  /** The optional TeamSpeak meeting time of day. */
  @Getter @Nullable private LocalTime meetingTime;

  /** The optional planned start time of day. */
  @Getter @Nullable private LocalTime plannedStartTime;

  /** The optional planned end time of day. */
  @Getter @Nullable private LocalTime plannedEndTime;

  /** The mission-wide radio channels in editor order. */
  @Getter
  @ElementCollection
  @CollectionTable(
      name = "mission_template_frequency",
      joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Frequency> frequencies = new ArrayList<>();

  /** The goals in editor order. */
  @Getter
  @ElementCollection
  @CollectionTable(
      name = "mission_template_objective",
      joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Objective> objectives = new ArrayList<>();

  /** The procedure in editor order. */
  @Getter
  @ElementCollection
  @CollectionTable(name = "mission_template_step", joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Step> steps = new ArrayList<>();

  /** The units in editor order. */
  @Getter
  @ElementCollection
  @CollectionTable(name = "mission_template_unit", joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Unit> units = new ArrayList<>();

  MissionTemplate(OrgUnit owningOrgUnit, String name, MissionTemplatePlan plan) {
    this.owningOrgUnit = owningOrgUnit;
    this.name = name;
    applyBasics(plan);
    frequencies.addAll(plan.frequencies());
    objectives.addAll(plan.objectives());
    steps.addAll(plan.steps());
    units.addAll(plan.units());
  }

  /**
   * Replaces the name and every part, leaving unchanged row collections untouched.
   *
   * @param newName the normalized name
   * @param plan the normalized replacement parts
   * @return whether any stored value changed
   */
  boolean replace(String newName, MissionTemplatePlan plan) {
    if (name.equals(newName) && plan().equals(plan)) {
      return false;
    }
    name = newName;
    applyBasics(plan);
    replaceRows(frequencies, plan.frequencies());
    replaceRows(objectives, plan.objectives());
    replaceRows(steps, plan.steps());
    replaceRows(units, plan.units());
    return true;
  }

  /**
   * Points the units planned with a ship type being merged away at the ship type that stays
   * (REQ-DATA-042).
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @return whether a unit changed
   */
  boolean reassignShipType(UUID retiredId, UUID survivorId) {
    List<MissionTemplatePlan.Unit> replaced =
        units.stream()
            .map(unit ->
                retiredId.equals(unit.shipTypeId())
                    ? new MissionTemplatePlan.Unit(
                        unit.name(),
                        survivorId,
                        unit.note(),
                        unit.highValueUnit(),
                        unit.frequency())
                    : unit)
            .toList();
    if (replaced.equals(units)) {
      return false;
    }
    replaceRows(units, replaced);
    return true;
  }

  private MissionTemplatePlan plan() {
    return new MissionTemplatePlan(
        description,
        meetingPoint,
        isInternal,
        meetingTime,
        plannedStartTime,
        plannedEndTime,
        List.copyOf(frequencies),
        List.copyOf(objectives),
        List.copyOf(steps),
        List.copyOf(units));
  }

  private void applyBasics(MissionTemplatePlan plan) {
    description = plan.description();
    meetingPoint = plan.meetingPoint();
    isInternal = plan.isInternal();
    meetingTime = plan.meetingTime();
    plannedStartTime = plan.plannedStartTime();
    plannedEndTime = plan.plannedEndTime();
  }

  private static <T> void replaceRows(List<T> existing, List<T> replacement) {
    if (!existing.equals(replacement)) {
      existing.clear();
      existing.addAll(replacement);
    }
  }
}
