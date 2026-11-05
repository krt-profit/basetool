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
 * One row of a P4K run's change report (REQ-P4K-014): identifiers, short names and fact values
 * only, never a game text. Report rows are never pruned.
 */
@Entity
@Table(name = "p4k_import_change")
@Getter
@Setter
@NoArgsConstructor
public class P4kImportChange {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The run the row belongs to. */
  @Column(name = "run_id", nullable = false)
  private UUID runId;

  /** The kind of the record. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private P4kKind kind;

  /** The P4K GUID of the record, or {@code null} when it has none. */
  @Column(name = "guid")
  @Nullable
  private UUID guid;

  /** The type of the change. */
  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", nullable = false, length = 32)
  private P4kChangeType changeType;

  /** The field the change concerns, or the review reason of a {@code REVIEW} row. */
  @Column(name = "field_name", length = 64)
  @Nullable
  private String fieldName;

  /** The value before the run, or {@code null}. */
  @Column(name = "old_value", columnDefinition = "TEXT")
  @Nullable
  private String oldValue;

  /** The value the run read, or {@code null}. */
  @Column(name = "new_value", columnDefinition = "TEXT")
  @Nullable
  private String newValue;

  /** The record's {@code className}. */
  @Column(name = "class_name", length = 255)
  @Nullable
  private String className;

  /** The record's {@code name.en}, a short name. */
  @Column(name = "name_en", length = 255)
  @Nullable
  private String nameEn;

  /** The record's {@code recordPath}. */
  @Column(name = "record_path", columnDefinition = "TEXT")
  @Nullable
  private String recordPath;
}
