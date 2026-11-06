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
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * A lasting review case of the P4K import (REQ-P4K-016): one entry per kind, GUID and reason, found
 * by the first run and touched by every later run that finds it again. A new case is {@code OPEN},
 * the database default of its {@code status} column.
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

  /** The Basetool row the case is about, or {@code null} for a record without one. */
  @Column(name = "row_id")
  @Nullable
  private UUID rowId;

  /** The run that found the case first. */
  @Column(name = "first_run_id", nullable = false, updatable = false)
  private UUID firstRunId;

  /** The last run that found the case. */
  @Column(name = "last_run_id", nullable = false)
  private UUID lastRunId;
}
