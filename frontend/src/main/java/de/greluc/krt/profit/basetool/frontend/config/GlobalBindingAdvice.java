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

import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Registers the normalising {@code String} property editor for every controller in the module.
 *
 * <p><strong>This advice is deliberately the one that is not scoped to {@link
 * UsesLayoutModel}.</strong> Its five siblings in this package contribute the Thymeleaf layout
 * model, which a {@code ResponseBody} handler can never read, so they select on that marker and
 * skip the REST controllers. This one contributes no model attribute at all — it configures the
 * {@link WebDataBinder}, and the REST controllers genuinely depend on it: Spring runs
 * {@code @RequestParam} and {@code @PathVariable} values of type {@code String} through {@code
 * WebDataBinder.convertIfNecessary}, so the editor below trims and length-caps them. The search
 * terms of {@code BankProxyController} and {@code UserProxyController}, the {@code handoff} of
 * {@code PersonalBlueprintImportProxyController}, the {@code domain} of {@code
 * AuditReportProxyController} and the {@code roleCode} of {@code OrgUnitBankProxyController} all
 * arrive through it. Narrowing this advice would silently lift the length cap on those, which is a
 * validation change, not a performance one.
 *
 * <p>{@code RequestBody} payloads are unaffected either way — Jackson deserialises those and never
 * consults the binder.
 */
@ControllerAdvice
public class GlobalBindingAdvice {
  /**
   * Registers {@link NormalizedStringEditor} for every controller so form-bound Strings are trimmed
   * and length-capped at {@link StringNormalization#MAX_FREE_TEXT_LENGTH} before validation runs.
   */
  @InitBinder
  public void initBinder(WebDataBinder binder) {
    binder.registerCustomEditor(
        String.class, new NormalizedStringEditor(StringNormalization.MAX_FREE_TEXT_LENGTH, true));
  }
}
