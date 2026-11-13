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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A lasting review case of the P4K import (REQ-P4K-016): one entry per kind, GUID and reason,
 * touched by every later run that finds it again. A case is {@code OPEN} until an admin resolves it
 * as keep or hide; a resolved case stays {@code REVIEWED} whatever a later run finds.
 */
@Entity
@Table(name = "p4k_review_case")
@Getter
@Setter
@NoArgsConstructor
public class P4kReviewCase extends AbstractEntity<UUID> {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The kind of the record. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private P4kKind kind;

  /** The P4K GUID of the record. */
  @Column(name = "guid", nullable = false)
  private UUID guid;

  /** Why the record is on the review list. */
  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 32)
  private P4kReviewReason reason;

  /** Whether the case waits for an admin. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  @NotNull
  private P4kReviewStatus status = P4kReviewStatus.OPEN;

  /** The Basetool row the case is about, or {@code null} for a record without one. */
  @Column(name = "row_id")
  @Nullable
  private UUID rowId;

  /** The row a GUID conflict between two rows collides with, or {@code null}. */
  @Column(name = "other_row_id")
  @Nullable
  private UUID otherRowId;

  /** The run that found the case first. */
  @Column(name = "first_run_id", nullable = false, updatable = false)
  private UUID firstRunId;

  /** The last run that found the case. */
  @Column(name = "last_run_id", nullable = false)
  private UUID lastRunId;

  /** The admin's decision, {@code null} while the case is open. */
  @Enumerated(EnumType.STRING)
  @Column(name = "resolution", length = 8)
  @Nullable
  private P4kReviewResolution resolution;

  /** The admin who resolved the case, {@code null} while it is open. */
  @Column(name = "resolved_by")
  @Nullable
  private UUID resolvedBy;

  /** When the case was resolved, {@code null} while it is open. */
  @Column(name = "resolved_at")
  @Nullable
  private Instant resolvedAt;

  /** The selected ship row, present while a base-identity repair waits for source data. */
  @Setter(AccessLevel.NONE)
  @Column(name = "ship_repair_row_id")
  @Nullable
  private UUID shipRepairRowId;

  /** The P4K variant GUID still held by the selected ship. */
  @Setter(AccessLevel.NONE)
  @Column(name = "ship_repair_variant_guid")
  @Nullable
  private UUID shipRepairVariantGuid;

  /** The current class of the stamped variant. */
  @Setter(AccessLevel.NONE)
  @Column(name = "ship_repair_variant_class", length = 255)
  @Nullable
  private String shipRepairVariantClass;

  /** The confirmed class of the incoming base record. */
  @Setter(AccessLevel.NONE)
  @Column(name = "ship_repair_base_class", length = 255)
  @Nullable
  private String shipRepairBaseClass;

  /** The reviewer who confirmed the repair. */
  @Setter(AccessLevel.NONE)
  @Column(name = "ship_repair_by")
  @Nullable
  private UUID shipRepairBy;

  /**
   * Whether a reviewer selected a repair that still needs verified P4K source data.
   *
   * @return whether the repair is pending
   */
  public boolean isShipRepairPending() {
    return shipRepairRowId != null;
  }

  /**
   * Records the row and both identities for revalidation on an eligible APPLY.
   *
   * @param shipId the selected ship row
   * @param variantGuid the P4K GUID currently stamped on the row
   * @param variantClass the currently stamped variant class
   * @param baseClass the confirmed base class
   * @param reviewer the administrator who confirmed the repair
   */
  void queueShipRepair(
      @NotNull UUID shipId,
      @NotNull UUID variantGuid,
      @NotNull String variantClass,
      @NotNull String baseClass,
      @NotNull UUID reviewer) {
    shipRepairRowId = shipId;
    shipRepairVariantGuid = variantGuid;
    shipRepairVariantClass = variantClass;
    shipRepairBaseClass = baseClass;
    shipRepairBy = reviewer;
  }

  /**
   * Closes the pending case after its ship and dependent catalogue data were rebuilt.
   *
   * @param now the completion time
   */
  void finishShipRepair(@NotNull Instant now) {
    rowId = shipRepairRowId;
    status = P4kReviewStatus.REVIEWED;
    resolution = P4kReviewResolution.APPLIED;
    resolvedBy = shipRepairBy;
    resolvedAt = now;
    cancelShipRepair();
  }

  /** Discards the pending choice while leaving the original case open. */
  void cancelShipRepair() {
    shipRepairRowId = null;
    shipRepairVariantGuid = null;
    shipRepairVariantClass = null;
    shipRepairBaseClass = null;
    shipRepairBy = null;
  }
}
