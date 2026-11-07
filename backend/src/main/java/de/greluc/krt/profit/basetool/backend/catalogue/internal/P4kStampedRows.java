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
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Counts the P4K-known rows of a kind that a sweep stamped as gone (REQ-OBS-022). */
@Component
@RequiredArgsConstructor
public class P4kStampedRows {

  private final EntityManager entityManager;

  /**
   * Counts the stamped rows of one kind.
   *
   * @param kind the kind
   * @return the rows with a P4K GUID and a {@code p4k_deleted_at} stamp
   */
  public long count(@NotNull P4kKind kind) {
    return entityManager
        .createQuery(P4kSweepTarget.of(kind).stampedRowsCountQuery(), Long.class)
        .getSingleResult();
  }
}
