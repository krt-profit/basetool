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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.uex;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Inbound JSON record for UEX Corp's <code>/companies</code> endpoint. Mapped to the project's own
 * {@code Manufacturer} entity by {@code UexVehicleService}; downstream code consumes the entity,
 * not this DTO.
 */
@Builder
public record UexCompanyDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("nickname") String nickname,
    @JsonProperty("wiki") String wiki,
    @JsonProperty("industry") String industry,
    @JsonProperty("is_item_manufacturer") Integer isItemManufacturer,
    @JsonProperty("is_vehicle_manufacturer") Integer isVehicleManufacturer,
    @JsonProperty("date_added") Long dateAdded,
    @JsonProperty("date_modified") Long dateModified) {
  public Boolean isVehicleManufacturerFlag() {
    return isVehicleManufacturer != null && isVehicleManufacturer == 1;
  }
}
