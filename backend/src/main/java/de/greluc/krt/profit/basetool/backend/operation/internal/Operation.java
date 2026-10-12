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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/** Operation JPA entity. */
@TenantScoped("owningOrgUnit")
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"missions"})
public class Operation extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "TEXT")
  @Nullable
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OperationStatus status;

  /**
   * Read-only view of the missions linked to this operation; the link is written only through
   * {@code Mission.operationId}.
   */
  @OneToMany
  @JoinColumn(name = "operation_id", insertable = false, updatable = false)
  @OrderBy("plannedStartTime DESC")
  private Set<Mission> missions = new HashSet<>();

  /**
   * Org-unit owner of this operation, stamped by the caller via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}.
   *
   * <p>{@code null} marks an organisation-wide leadership operation, visible to organisation
   * members and above only (REQ-ORG-009).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  @Nullable
  private OrgUnit owningOrgUnit;
}
