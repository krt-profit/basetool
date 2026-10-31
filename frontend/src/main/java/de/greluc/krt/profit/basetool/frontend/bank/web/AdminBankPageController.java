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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for the admin-only bank wipe reset (REQ-BANK-013), whose card sits on the danger-zone
 * tab of the system settings; bank management has no access (REQ-BANK-010). The wipe reset is a
 * form post guarded by a type-to-confirm step.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminBankPageController {

  /** The danger-zone tab of the system settings. */
  private static final String DANGER_ZONE = "/admin/settings/danger-zone";

  /** The bank domain's backend calls. */
  private final BankBackendClient bankClient;

  /**
   * Redirects {@code /admin/bank-audit} to the unified audit-log page with the bank tab selected.
   *
   * @return a redirect to the unified audit-log page, bank tab
   */
  @NotNull
  @GetMapping("/admin/bank-audit")
  public String bankAuditRedirect() {
    return "redirect:/admin/audit-log?domain=BANK";
  }

  /**
   * Runs the wipe reset and redirects back with the affected counts or an error flag. {@code
   * confirm} must equal {@code WIPE}.
   *
   * @param confirm the type-to-confirm token; must equal {@code WIPE}
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to the danger-zone tab
   */
  @NotNull
  @PostMapping("/admin/bank/wipe-reset")
  public String wipeReset(
      @RequestParam(required = false) String confirm, RedirectAttributes redirectAttributes) {
    if (!"WIPE".equals(confirm)) {
      redirectAttributes.addFlashAttribute("error", "admin.bank.wipe.error.confirm");
      return "redirect:" + DANGER_ZONE;
    }
    try {
      BankWipeResetResultDto result = bankClient.wipeReset();
      if (result == null || result.accountsReset() == 0) {
        redirectAttributes.addFlashAttribute("wipeNoop", true);
      } else {
        redirectAttributes.addFlashAttribute("wipeResult", result);
      }
    } catch (Exception e) {
      log.error("Bank wipe reset failed", e);
      redirectAttributes.addFlashAttribute("error", "admin.bank.wipe.error.failed");
    }
    return "redirect:" + DANGER_ZONE;
  }

  /**
   * AJAX variant of {@link #wipeReset}, returning {@code {"accountsReset": <n>,
   * "holderStashesZeroed": <m>}}. {@code confirm} must equal {@code WIPE}.
   *
   * @param confirm the type-to-confirm token; must equal {@code WIPE}
   * @return {@code 200} with the counts on success, {@code 400} when the confirm token is wrong,
   *     the relayed backend {@code problem+json} on a backend error, or {@code 500} on an
   *     unclassified failure
   */
  @ResponseBody
  @PostMapping(value = "/admin/bank/wipe-reset", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> wipeResetAjax(@RequestParam(required = false) String confirm) {
    if (!"WIPE".equals(confirm)) {
      return ResponseEntity.badRequest().build();
    }
    return relay(
        log,
        "bank wipe reset (ajax)",
        () -> {
          BankWipeResetResultDto result = bankClient.wipeReset();
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("accountsReset", result == null ? 0 : result.accountsReset());
          body.put("holderStashesZeroed", result == null ? 0 : result.holderStashesZeroed());
          return ResponseEntity.ok(body);
        });
  }
}
