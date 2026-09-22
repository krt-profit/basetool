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

package de.greluc.krt.profit.basetool.backend.mapper;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct configuration inherited by every mapper via {@code @Mapper(config =
 * CentralMapperConfig.class)}. It centralises three settings (REQ-API-002):
 *
 * <ul>
 *   <li>{@code componentModel = "spring"} — MapStruct emits Spring {@code @Component} beans.
 *   <li>{@code injectionStrategy = CONSTRUCTOR} — a generated mapper receives the mappers it {@code
 *       uses} through its constructor, never through {@code @Autowired} fields, matching the
 *       constructor-injection rule the rest of the code base follows.
 *   <li>{@code unmappedTargetPolicy = ERROR} — a target property no source feeds <em>fails the
 *       build</em>. A DTO field added later would otherwise ship silently {@code null}; every gap
 *       that is intended is spelled out with {@code @Mapping(target = "...", ignore = true)} on the
 *       method, so a reader sees what is deliberately left unset.
 * </ul>
 *
 * <p>Pointing every mapper at this config replaces the per-mapper boilerplate, which previously
 * spelled the policy three different ways, with a single authoritative declaration.
 */
@MapperConfig(
    componentModel = "spring",
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CentralMapperConfig {}
