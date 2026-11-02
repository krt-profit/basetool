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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Marks a {@link Controller} whose handlers render a fragment that a {@code krtFetch} call swaps
 * into a page, not a page of their own: it opts into the shared layout model ({@link
 * UsesLayoutModel}) and carries the common path of its handlers. The handlers state only their
 * reads, with {@link FragmentGetMapping}.
 *
 * <p>Applied alongside {@link Controller}, which stays on the class so the architecture rules that
 * look for it keep seeing the controller.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@UsesLayoutModel
@RequestMapping
public @interface FragmentController {

  /**
   * The path every handler of the controller shares.
   *
   * @return the paths, none for a controller whose handlers state their whole path
   */
  @AliasFor(annotation = RequestMapping.class, attribute = "value")
  String[] value() default {};
}
