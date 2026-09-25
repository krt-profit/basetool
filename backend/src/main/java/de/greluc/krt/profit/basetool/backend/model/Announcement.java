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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Announcement JPA entity.
 *
 * <p>The {@code updatedAt} column lives on {@link AbstractEntity} where {@code @UpdateTimestamp}
 * keeps it fresh on every {@code persist}/{@code update}. Earlier revisions shadowed that field
 * here with a manual {@code @PrePersist}/{@code @PreUpdate} hook — the duplicate has been removed
 * to avoid the JPA column-mapping ambiguity and to silence the CodeQL "missing {@code @Override} on
 * {@code getUpdatedAt}" finding that the shadowing produced.
 */
@Entity
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Announcement extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Getter
  @Column(columnDefinition = "TEXT")
  private String content;
}
