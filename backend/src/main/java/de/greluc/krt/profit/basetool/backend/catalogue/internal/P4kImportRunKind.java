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
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * The completeness and the result counts of one kind in one P4K import run (REQ-P4K-005), a row of
 * {@code p4k_import_run_kind}.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class P4kImportRunKind {

  /** The kind. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private P4kKind kind;

  /** Whether the catalogue listed the kind completely. */
  @Enumerated(EnumType.STRING)
  @Column(name = "completeness", nullable = false, length = 16)
  private P4kCompleteness completeness;

  /** The p4kx Filter of a partial kind, else {@code null}. */
  @Column(name = "completeness_filter", columnDefinition = "TEXT")
  @Nullable
  private String completenessFilter;

  /** Catalogue records matched to a Basetool row. */
  @Column(name = "matched", nullable = false)
  private int matched;

  /** Catalogue records with no Basetool row, each an {@code UNMATCHED} report row. */
  @Column(name = "unmatched", nullable = false)
  private int unmatched;

  /** Rows the run created. */
  @Column(name = "added", nullable = false)
  private int added;

  /** Rows the run's sweep stamped as gone (REQ-P4K-011). */
  @Column(name = "vanished", nullable = false)
  private int vanished;

  /** Stamped rows the run matched again (REQ-P4K-011). */
  @Column(name = "reappeared", nullable = false)
  private int reappeared;

  /** Matched rows the run changed (REQ-P4K-014). */
  @Column(name = "changed", nullable = false)
  private int changed;

  /** What the sweep did with the kind, {@code null} on a run recorded before the sweep existed. */
  @Enumerated(EnumType.STRING)
  @Column(name = "sweep", length = 24)
  @Nullable
  private P4kSweepOutcome sweep;
}
