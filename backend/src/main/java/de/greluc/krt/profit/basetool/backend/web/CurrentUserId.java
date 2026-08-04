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

package de.greluc.krt.profit.basetool.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the authenticated caller's {@code sub} claim, parsed to a {@link java.util.UUID}, to a
 * controller method parameter — the UUID-typed twin of {@link CurrentUserSub}.
 *
 * <p>Resolved by {@link CurrentUserArgumentResolver}, which reads the subject rather than the
 * authentication type — so this also binds for the member an ingest-gateway call acts for, who
 * carries a subject and no token (ADR-0129). A missing or blank subject and a subject that is not a
 * valid UUID each raise {@link org.springframework.security.access.AccessDeniedException} (HTTP
 * 403).
 *
 * <p>Parameters carrying this annotation are hidden from the generated OpenAPI document (registered
 * in {@link de.greluc.krt.profit.basetool.backend.config.OpenApiConfig}).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {}
