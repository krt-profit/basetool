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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  @Column(nullable = false)
  private String name;

  /** The goals in editor order. */
  @ElementCollection
  @CollectionTable(
      name = "mission_template_objective",
      joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Objective> objectives = new ArrayList<>();

  /** The procedure in editor order. */
  @ElementCollection
  @CollectionTable(name = "mission_template_step", joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Step> steps = new ArrayList<>();

  /** The units in editor order. */
  @ElementCollection
  @CollectionTable(name = "mission_template_unit", joinColumns = @JoinColumn(name = "template_id"))
  @OrderColumn(name = "order_index")
  private List<MissionTemplatePlan.Unit> units = new ArrayList<>();

  MissionTemplate(OrgUnit owningOrgUnit, String name, MissionTemplatePlan plan) {
    this.owningOrgUnit = owningOrgUnit;
    this.name = name;
    objectives.addAll(plan.objectives());
    steps.addAll(plan.steps());
    units.addAll(plan.units());
  }

  boolean replace(String newName, MissionTemplatePlan plan) {
    boolean nameChanged = !name.equals(newName);
    name = newName;
    boolean objectivesChanged = replaceRows(objectives, plan.objectives());
    boolean stepsChanged = replaceRows(steps, plan.steps());
    boolean unitsChanged = replaceRows(units, plan.units());
    return nameChanged || objectivesChanged || stepsChanged || unitsChanged;
  }

  private static <T> boolean replaceRows(List<T> existing, List<T> replacement) {
    if (existing.equals(replacement)) {
      return false;
    }
    existing.clear();
    existing.addAll(replacement);
    return true;
  }

  MissionTemplateDto detail() {
    return new MissionTemplateDto(
        Objects.requireNonNull(id),
        owningOrgUnit.getId(),
        name,
        getVersion(),
        List.copyOf(objectives),
        List.copyOf(steps),
        List.copyOf(units));
  }
}
