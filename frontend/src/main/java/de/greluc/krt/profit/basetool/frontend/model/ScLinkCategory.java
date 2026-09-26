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

package de.greluc.krt.profit.basetool.frontend.model;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/** The sections of the „Star-Citizen-Links" page (REQ-UI-025), rendered in declaration order. */
@Getter
@RequiredArgsConstructor
public enum ScLinkCategory {
  ORGANISATION("organisation"),
  TRADE("trade"),
  SHIPS("ships"),
  DATABASES("databases"),
  UNIVERSE("universe");

  /** Suffix of the section's heading key {@code scLinks.category.<key>}. */
  @NotNull private final String key;

  /**
   * Lists the links filed under this section.
   *
   * @return the links of this section in {@link ScLink} declaration order
   */
  @NotNull
  @Unmodifiable
  public List<ScLink> links() {
    return Arrays.stream(ScLink.values()).filter(link -> link.getCategory() == this).toList();
  }
}
