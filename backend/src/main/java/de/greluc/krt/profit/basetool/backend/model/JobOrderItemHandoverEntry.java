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

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One delivered line of a {@link JobOrderItemHandover}: a whole-unit {@link #amount} of a specific
 * {@link JobOrderItem} that changed hands during the handover. Persisting the reference to the
 * ordered line (rather than the bare game item) lets fulfilment increment that line's {@code
 * deliveredAmount} directly and drives the order's auto-completion check.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "job_order_item_handover_entry")
public class JobOrderItemHandoverEntry extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Owning item handover. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_item_handover_id", nullable = false)
  private JobOrderItemHandover jobOrderItemHandover;

  /** The ordered item line this delivery fulfils. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_item_id", nullable = false)
  private JobOrderItem jobOrderItem;

  /** Number of whole units delivered for the referenced line in this handover. */
  @Column(nullable = false)
  private Integer amount;
}
