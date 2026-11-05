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

package de.greluc.krt.profit.basetool.backend.kernel;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a column that holds game media (red): an image, a sound or another asset taken from the
 * game archive (REQ-P4K-033, ADR-0344).
 *
 * <p>Its value is shown to logged-in members only and never leaves the Basetool to a third party;
 * code of an outbound integration must not call the getter of a field carrying it (REQ-XCH-050). No
 * column carries it while the Basetool stores no archive media; the UEX link and image columns are
 * UEX data and stay unmarked. A field carries at most one of {@link GameText} and {@code
 * GameMedia}.
 */
@Documented
@Target(FIELD)
@Retention(RUNTIME)
public @interface GameMedia {}
