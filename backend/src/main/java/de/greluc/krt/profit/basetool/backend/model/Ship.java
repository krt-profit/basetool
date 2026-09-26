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

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Ship JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Ship extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ship_type_id", nullable = false)
  @ToString.Exclude
  private ShipType shipType;

  @NotBlank(message = "{validation.insurance.required}")
  @Pattern(
      regexp = "^(0|([1-9]|[1-9][0-9]|1[0-1][0-9]|120)|LTI)$",
      message = "{validation.insurance.pattern}")
  private String insurance;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "location_id")
  @ToString.Exclude
  private Location location;

  private boolean fitted;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id", nullable = false)
  @ToString.Exclude
  private User owner;

  /**
   * Org-unit owner of this ship, or {@code null} for an ownerless personal ship added by a user in
   * no Staffel/SK. Such a ship is visible and editable only by its {@link #owner} (plus admins in
   * all-scopes mode) and never appears in an org unit's hangar. Stamped via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id", nullable = true)
  private OrgUnit owningOrgUnit;
}
