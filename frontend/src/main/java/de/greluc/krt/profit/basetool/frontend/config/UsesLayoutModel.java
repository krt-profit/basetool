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
 * Marks a {@link Controller} whose handlers render Thymeleaf views and therefore need the shared
 * layout model.
 *
 * <p>It is the selector for the layout advices in this package — {@link OrgUnitContextAdvice},
 * {@link CapabilityFlagsAdvice}, {@link LayoutMiscAdvice}, {@link AppVersionAdvice} and {@link
 * SafeCsrfAdvice} all select on it. Without a selector Spring's {@code ModelFactory} runs an advice
 * ahead of <em>every</em> handler in the module, and it runs before the handler regardless of
 * whether that handler is a {@code ResponseBody} one. The module's 17 REST controllers were
 * therefore paying for a model Jackson can never serialise: three of the five advices reach the
 * backend, so one authenticated JSON call cost five backend reads — four uncached, plus the cached
 * squadron page-walk — before its own work began.
 *
 * <p><strong>This is an opt-in marker, deliberately.</strong> A controller added without it gets no
 * layout model and costs nothing, which is the right default for the JSON endpoints that carry the
 * in-place mutation surface (REQ-FE-001..REQ-FE-010). Selecting on {@link Controller} itself would
 * not work: {@code RestController} is meta-annotated with it and Spring matches through
 * meta-annotations, so that predicate selects exactly the controllers to be excluded. A
 * base-package predicate does not work either — Spring matches it with {@code
 * Class.getName().startsWith(...)}, so a sibling package under the controller package still matches
 * its parent.
 *
 * <p>It is applied <em>alongside</em> {@link Controller} rather than meta-annotated with it, so the
 * authorization gate in {@code ArchitectureTest} — which matches {@link Controller} directly, not
 * through meta-annotations — keeps seeing every controller in the module. That test also pins both
 * halves of this split: every non-REST controller carries this marker, and no REST controller does.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UsesLayoutModel {}
