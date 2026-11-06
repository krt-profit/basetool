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

import de.greluc.krt.profit.basetool.backend.kernel.AbstractEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One successful P4K APPLY (REQ-P4K-005, ADR-0335): the Channel, the Build, the patch version and
 * its origin, the tool and export time, the uploading admin, the apply time and the completeness
 * and counts per kind. A PREVIEW writes none; runs are never pruned.
 */
@Entity
@Table(name = "p4k_import_run")
@Getter
@Setter
@NoArgsConstructor
public class P4kImportRun extends AbstractEntity<UUID> {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The APPLY job that wrote the run; {@code null} once the job is pruned. */
  @Column(name = "job_id")
  @Nullable
  private UUID jobId;

  /** The Channel, always {@code LIVE}. */
  @Column(name = "channel", nullable = false, length = 32)
  private String channel;

  /** {@code source.build.branch}. */
  @Column(name = "build_branch", nullable = false, length = 128)
  private String buildBranch;

  /** {@code source.build.version}, for display only. */
  @Column(name = "build_version", nullable = false, length = 64)
  private String buildVersion;

  /** {@code source.build.changeNumber} as a number; orders the runs (REQ-P4K-004). */
  @Column(name = "change_number", nullable = false)
  private long changeNumber;

  /** {@code source.build.buildId}, for equality only. */
  @Column(name = "build_id", nullable = false, length = 64)
  private String buildId;

  /** The patch version, or {@code null} when it is unknown. */
  @Column(name = "patch_version", length = 32)
  @Nullable
  private String patchVersion;

  /** Where {@link #patchVersion} comes from; {@code null} exactly when it is unknown. */
  @Enumerated(EnumType.STRING)
  @Column(name = "patch_version_origin", length = 24)
  @Nullable
  private P4kPatchVersionOrigin patchVersionOrigin;

  /** {@code source.toolVersion}, the p4kx version. */
  @Column(name = "tool_version", length = 64)
  @Nullable
  private String toolVersion;

  /** {@code source.exportedAt}, or {@code null} when it is not RFC 3339 text. */
  @Column(name = "exported_at")
  @Nullable
  private Instant exportedAt;

  /** JWT {@code sub} of the administrator who uploaded the catalogue. */
  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  /** When the APPLY ran. */
  @Column(name = "applied_at", nullable = false)
  private Instant appliedAt;

  /** The completeness and counts per kind. */
  @ElementCollection(fetch = FetchType.EAGER)
  @BatchSize(size = 50)
  @CollectionTable(name = "p4k_import_run_kind", joinColumns = @JoinColumn(name = "run_id"))
  @NotNull
  private List<P4kImportRunKind> kinds = new ArrayList<>();
}
