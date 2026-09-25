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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

/**
 * Join entity linking a {@link JobOrder} to a {@link User} who signed up to work on it (a
 * "Bearbeiter"), with an optional free-text note.
 *
 * <p>Carries its own {@code @Version}, so editing a note never bumps the parent {@link JobOrder}.
 * The {@code (job_order_id, user_id)} pair is unique.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
    name = "job_order_assignees",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_job_order_assignee",
            columnNames = {"job_order_id", "user_id"}))
public class JobOrderAssignee extends AbstractEntity<UUID> {

  /**
   * Maximum length, in characters, of the optional {@link #note}. Enforced identically at the DTO
   * validation boundary ({@code @Size}), by this column definition, and by the Flyway migration, so
   * the cap holds end-to-end.
   */
  public static final int NOTE_MAX_LENGTH = 500;

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** The order this assignment belongs to. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "job_order_id", nullable = false)
  private JobOrder jobOrder;

  /** The user who signed up to work on the order. */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /**
   * Optional free-text note the assignee (or a Logistician+) attached to this entry — context such
   * as when the assignee works on the order or which part they handle. Plain text, always rendered
   * HTML-escaped in the UI, bounded to {@value #NOTE_MAX_LENGTH} characters; {@code null} when no
   * note is set.
   */
  @Nullable
  @Column(name = "note", length = NOTE_MAX_LENGTH)
  private String note;
}
