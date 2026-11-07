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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * A blueprint category of the P4K catalogue ({@code blueprintCategories}), keyed by its P4K GUID;
 * categories do not nest and no other source lists them (REQ-P4K-027, ADR-0340).
 */
@Entity
@Table(name = "blueprint_category")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BlueprintCategory extends AbstractEntity<UUID> implements P4kKeyedRow {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  /** The P4K GUID, the category's identity. */
  @Column(name = "p4k_uuid", nullable = false, unique = true)
  private UUID p4kUuid;

  /** The Record name, e.g. {@code FPSArmours}. */
  @Column(name = "class_name", nullable = false)
  private String className;

  /** The English name, or {@code null} while the game localises none. */
  @Column(name = "name")
  @Nullable
  private String name;

  /** The German name, or {@code null} while the game localises none. */
  @Column(name = "name_de")
  @Nullable
  private String nameDe;

  /** The last P4K match. */
  @Column(name = "p4k_synced_at")
  @Nullable
  private Instant p4kSyncedAt;

  /** When the P4K sweep found the category gone from a complete catalogue, or {@code null}. */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /** When an admin hid the category through a P4K review case, or {@code null}. */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /** The P4K import run that last matched this category, or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;

  /**
   * Returns the name a category shows: its English name, or its class name while it has none.
   *
   * @return the display name
   */
  public String displayName() {
    return name == null || name.isBlank() ? className : name;
  }
}
