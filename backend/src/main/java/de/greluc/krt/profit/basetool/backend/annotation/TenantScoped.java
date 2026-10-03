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

package de.greluc.krt.profit.basetool.backend.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA entity as the root of an org-unit-scoped aggregate: its rows belong to the org unit
 * held by the named association, and every read and write of it is filtered or gated by the scope
 * service (REQ-ORG-002, REQ-ORG-003, REQ-ORG-028).
 *
 * <p>The tenancy guards select by this marker rather than by class names, so a split or re-cut
 * controller that writes a marked aggregate stays under the scope-gate rule.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {

  /**
   * Names the entity fields that hold the owning or responsible org unit the scope rules filter on.
   *
   * @return the field names, each an association to an org unit declared on the entity or one of
   *     its mapped superclasses
   */
  String[] value();
}
