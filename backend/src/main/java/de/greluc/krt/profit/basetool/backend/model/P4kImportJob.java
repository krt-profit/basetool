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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One asynchronous KRT P4K Reader catalog-import run, either a {@link P4kImportJobKind#PREVIEW
 * PREVIEW} or an {@link P4kImportJobKind#APPLY APPLY} launched from a finished preview.
 *
 * <p>An {@code @Async} worker moves it from {@link P4kImportJobStatus#PENDING PENDING} through
 * {@link P4kImportJobStatus#RUNNING RUNNING} to {@link P4kImportJobStatus#SUCCEEDED SUCCEEDED}
 * (with {@link #resultJson}) or {@link P4kImportJobStatus#FAILED FAILED} (with {@link
 * #errorMessage}). The upload itself lives in the 1:1 {@link P4kImportJobPayload} side table.
 */
@Entity
@Table(name = "p4k_import_job")
@Getter
@Setter
@NoArgsConstructor
public class P4kImportJob extends AbstractEntity<UUID> {

  /** Surrogate primary key, assigned by Hibernate before insert. */
  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Whether this run previews (dry) or applies the catalog. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 16)
  private P4kImportJobKind kind;

  /** Lifecycle status, advanced by the import worker. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private P4kImportJobStatus status;

  /** For an APPLY run: whether brand-new unmatched player-facing rows are seeded. */
  @Column(name = "seed_new", nullable = false)
  private boolean seedNew;

  /** Original upload filename, for display in the job list (forensic; may be {@code null}). */
  @Column(name = "source_filename", length = 255)
  private String sourceFilename;

  /** Size of the uploaded catalog in bytes, for display (may be {@code null}). */
  @Column(name = "file_size_bytes")
  private Long fileSizeBytes;

  /**
   * The serialized {@code P4kImportResultDto} as plain JSON text once the run succeeds; {@code
   * null} while pending, running or on failure.
   */
  @Column(name = "result_json", columnDefinition = "TEXT")
  private String resultJson;

  /** Human-readable failure reason when {@link #status} is {@code FAILED}, else {@code null}. */
  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  /**
   * For an APPLY run, the PREVIEW job it was launched from (informational only; the APPLY holds its
   * own copy of the upload); {@code null} for a PREVIEW run.
   */
  @Column(name = "preview_job_id")
  private UUID previewJobId;

  /** JWT {@code sub} of the administrator who enqueued the run. */
  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  /** When the worker picked the job up (set on {@code RUNNING}), or {@code null}. */
  @Column(name = "started_at")
  private Instant startedAt;

  /** When the worker finished (set on {@code SUCCEEDED} / {@code FAILED}), or {@code null}. */
  @Column(name = "finished_at")
  private Instant finishedAt;
}
