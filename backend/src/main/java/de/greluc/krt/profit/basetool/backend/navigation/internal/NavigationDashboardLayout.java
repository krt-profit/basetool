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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One member's dashboard layout of one mode: the layout version and whether the member changed the
 * default (REQ-UI-034). The version is bumped by a conditional {@code UPDATE}, never through the
 * entity.
 */
@Entity
@Table(name = "navigation_dashboard_layout")
@Getter
@NoArgsConstructor
public class NavigationDashboardLayout {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** The root row of the owner. */
  @Column(name = "preferences_id", nullable = false, updatable = false)
  private UUID preferencesId;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false, updatable = false, length = 16)
  private DashboardMode mode;

  @Column(name = "layout_version", nullable = false, insertable = false, updatable = false)
  private long layoutVersion;

  /** Whether the stored widgets replace the mode's default. */
  @Column(name = "customised", nullable = false, insertable = false, updatable = false)
  private boolean customised;
}
