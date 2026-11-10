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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Keeps once-only physical provision evidence independently of deletable aggregate rows. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "material_provision_credit")
public class MaterialProvisionCredit extends AbstractEntity<UUID> {
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The historical order identity; aggregate deletion cannot erase physical once-credit state. */
  @Column(name = "job_order_id", nullable = false)
  private UUID orderId;

  /** The contribution whose open amount this provision actually fulfilled. */
  @Column(name = "claim_id", nullable = false)
  private UUID claimId;

  /** The stable Staffel participation credited by this fact. */
  @Column(name = "participation_id", nullable = false)
  private UUID participationId;

  /** The explicitly credited unit, never inferred from recorder or stock owner. */
  @Column(name = "claiming_org_unit_id", nullable = false)
  private UUID claimingOrgUnitId;

  /** The provisioned material. */
  @Column(name = "material_id", nullable = false)
  private UUID materialId;

  /** The selected requirement's immutable quality-tier identity. */
  @Column(name = "quality_tier_id", nullable = false)
  private UUID qualityTierId;

  /** The storage row observed at provision, retained as evidence after relocation or deletion. */
  @Column(name = "source_row_id", nullable = false)
  private UUID sourceRowId;

  /** The currently truthful credited quantity, distinct from dynamically covered stock. */
  @Column(name = "amount", nullable = false)
  private Double amount;

  /** Physical intervals already processed, retained even when a false fact is corrected. */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "material_provision_fragment",
      joinColumns = @JoinColumn(name = "credit_id"))
  private List<MaterialProvisionFragment> fragments = new ArrayList<>();
}
