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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a frontend DTO record component that deliberately mirrors a backend enum property as its
 * {@link String} name.
 *
 * <p>{@code FrontendDtoContractTest} requires this annotation wherever {@code openapi.json} types a
 * property as an enum but the frontend component is a {@code String}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface BackendEnumAsString {

  /**
   * Optional note documenting the demotion — typically the i18n key prefix the enum name feeds
   * (e.g. {@code "bank.account.type"}). Purely informational: the contract test only checks that
   * the annotation is present, not its value.
   *
   * @return the documenting note, or the empty string when none is given
   */
  String value() default "";
}
