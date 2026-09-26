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

package de.greluc.krt.profit.basetool.frontend.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Controller;

/**
 * Marks a {@link Controller} whose handlers render Thymeleaf views and need the shared layout
 * model.
 *
 * <p>The layout advices ({@link OrgUnitContextAdvice}, {@link CapabilityFlagsAdvice}, {@link
 * LayoutMiscAdvice}, {@link AppVersionAdvice}, {@link SafeCsrfAdvice}) select on it, so REST
 * controllers skip their backend calls. Opt-in, applied alongside {@link Controller} rather than
 * meta-annotated with it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UsesLayoutModel {}
