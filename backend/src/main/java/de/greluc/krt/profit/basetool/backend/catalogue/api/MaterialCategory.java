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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/**
 * A commodity category: a resource group of the P4K catalogue, keyed by its P4K GUID (REQ-P4K-025,
 * ADR-0339).
 */
@Entity
@Table(name = "material_category")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCategory extends AbstractEntity<UUID> implements P4kKeyedRow {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Setter(onParam_ = @__(@Nullable))
  private UUID id;

  /** The name shown: the group's English name, or its class name while that is a placeholder. */
  @Column(nullable = false)
  private String name;

  /** The German name from the P4K, or {@code null} when the game has none (REQ-P4K-023). */
  @Column(name = "name_de")
  @Nullable
  private String nameDe;

  /** The group's Record name, e.g. {@code Metal}; {@code null} on a row the P4K never matched. */
  @Column(name = "class_name")
  @Nullable
  private String className;

  /** The group that lists this one, {@code null} for a top-level group; never forms a cycle. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  @ToString.Exclude
  @Nullable
  private MaterialCategory parent;

  /** The P4K GUID, the row's identity in the catalogue and unique. */
  @Column(name = "p4k_uuid", unique = true)
  @Nullable
  private UUID p4kUuid;

  /** The last P4K match, or {@code null}. */
  @Column(name = "p4k_synced_at")
  @Nullable
  private Instant p4kSyncedAt;

  /** When the P4K sweep found the group gone from a complete catalogue, or {@code null}. */
  @Column(name = "p4k_deleted_at")
  @Nullable
  private Instant p4kDeletedAt;

  /** When an admin hid the group through a P4K review case, or {@code null}. */
  @Column(name = "p4k_hidden_at")
  @Nullable
  private Instant p4kHiddenAt;

  /** The P4K import run that last matched this group, or {@code null}. */
  @Column(name = "p4k_seen_run_id")
  @Nullable
  private UUID p4kSeenRunId;
}
