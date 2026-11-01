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
import lombok.Setter;

/**
 * The „Darstellung" values of one member (REQ-UI-032); the row exists once the member chose, and
 * references the {@link NavigationPreferences} root, which carries the section version.
 */
@Entity
@Table(name = "navigation_appearance")
@Getter
@NoArgsConstructor
public class NavigationAppearance {

  /** The owner's {@code app_user.id}, which is also the root's key. */
  @Id
  @Column(name = "preferences_id", nullable = false, updatable = false)
  private UUID preferencesId;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "desktop_mode", nullable = false, length = 16)
  private DesktopMode desktopMode = DesktopMode.DRAWER;

  /**
   * Creates the appearance of a member.
   *
   * @param preferencesId the owner's {@code app_user.id}
   * @param desktopMode the chosen desktop mode
   */
  public NavigationAppearance(UUID preferencesId, DesktopMode desktopMode) {
    this.preferencesId = preferencesId;
    this.desktopMode = desktopMode;
  }
}
