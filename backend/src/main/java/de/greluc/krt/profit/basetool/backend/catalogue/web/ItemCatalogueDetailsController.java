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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueDetailsDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ItemCatalogueDetailsService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Member-only typed catalogue evidence for an existing game-item reference. */
@RestController
@RequestMapping("/api/v1/game-items")
@RequiredArgsConstructor
@PreAuthorize(
    "hasAnyRole('" + Roles.ADMIN + "','" + Roles.LOGISTICIAN + "','" + Roles.KRT_MEMBER + "')")
public class ItemCatalogueDetailsController {
  private final ItemCatalogueDetailsService service;

  /**
   * Returns source-attributed catalogue details without treating derived prices as authoritative.
   *
   * @param id current or redirected local item id
   * @return item details
   */
  @GetMapping("/{id}/catalogue")
  public ItemCatalogueDetailsDto get(@PathVariable UUID id) {
    return service.get(id);
  }
}
