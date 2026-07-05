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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin bank pages (epic #556 Phase 4, REQ-BANK-013): the wipe-reset
 * danger card ({@code /admin/bank}, A1 mockup). Admin-only — class-level {@code @PreAuthorize}
 * matches the backend's admin URL gate; bank management does NOT reach these pages (REQ-BANK-010).
 *
 * <p>The audit-log viewer moved to the unified {@code /admin/audit-log} page (REQ-AUDIT-001,
 * ADR-0037); the legacy {@code /admin/bank-audit} URL redirects there with the bank tab preselected
 * so old bookmarks keep working.
 *
 * <p>The wipe-reset is a server-side PRG form post (not AJAX) gated by a type-to-confirm hurdle in
 * the browser; the backend re-enforces the admin role and the idempotency.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminBankPageController {

  private final BackendApiClient backendApiClient;

  /**
   * Renders the wipe-reset danger card. The page is static except for the PRG flash attributes,
   * which Spring exposes to the template without a model parameter.
   *
   * @return the {@code admin/bank} view name
   */
  @GetMapping("/admin/bank")
  public String bankAdmin() {
    return "admin/bank";
  }

  /**
   * Redirects the legacy bank-audit URL to the unified audit-log page with the bank tab selected
   * (REQ-AUDIT-001) so existing bookmarks and links keep working.
   *
   * @return a redirect to the unified audit-log page, bank tab
   */
  @GetMapping("/admin/bank-audit")
  public String bankAuditRedirect() {
    return "redirect:/admin/audit-log?domain=BANK";
  }

  /**
   * Executes the wipe reset against the backend and redirects back with a flash result: the
   * affected counts on success (incl. the idempotent no-op = zero counts), or an error flag on
   * failure. The {@code confirm} field must equal {@code WIPE} — a server-side backstop to the
   * type-to-confirm modal so a crafted POST without the hurdle is still rejected.
   *
   * @param confirm the type-to-confirm token; must equal {@code WIPE}
   * @param redirectAttributes flash attributes carrier
   * @return redirect back to the admin bank page
   */
  @PostMapping("/admin/bank/wipe-reset")
  public String wipeReset(
      @RequestParam(required = false) String confirm, RedirectAttributes redirectAttributes) {
    if (!"WIPE".equals(confirm)) {
      redirectAttributes.addFlashAttribute("error", "admin.bank.wipe.error.confirm");
      return "redirect:/admin/bank";
    }
    try {
      BankWipeResetResultDto result =
          backendApiClient.post(
              "/api/v1/bank/admin/wipe-reset", Map.of(), BankWipeResetResultDto.class);
      if (result == null || result.accountsReset() == 0) {
        redirectAttributes.addFlashAttribute("wipeNoop", true);
      } else {
        redirectAttributes.addFlashAttribute("wipeResult", result);
      }
    } catch (Exception e) {
      log.error("Bank wipe reset failed", e);
      redirectAttributes.addFlashAttribute("error", "admin.bank.wipe.error.failed");
    }
    return "redirect:/admin/bank";
  }

  /**
   * In-place (AJAX) twin of {@link #wipeReset} — routed here ahead of the classic handler by the
   * {@code X-Requested-With} header so the no-JS form keeps its redirect fallback. Returns the
   * affected counts as {@code {"accountsReset": <n>, "holderStashesZeroed": <m>}} so the page can
   * show a success/no-op toast in place instead of reloading. The {@code WIPE} confirm token is
   * re-checked server-side as a backstop to the type-to-confirm modal.
   *
   * @param confirm the type-to-confirm token; must equal {@code WIPE}
   * @return {@code 200} with the counts on success, {@code 400} when the confirm token is wrong, a
   *     relayed {@code problem+json} carrying the backend status and {@code code} (e.g. {@code
   *     PESSIMISTIC_LOCK} when the wipe races a concurrent booking) so {@code krtFetch} can offer
   *     the reload-confirm, or {@code 500} on an otherwise-unclassified backend failure
   */
  @ResponseBody
  @PostMapping(value = "/admin/bank/wipe-reset", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> wipeResetAjax(@RequestParam(required = false) String confirm) {
    if (!"WIPE".equals(confirm)) {
      return ResponseEntity.badRequest().build();
    }
    try {
      BankWipeResetResultDto result =
          backendApiClient.post(
              "/api/v1/bank/admin/wipe-reset", Map.of(), BankWipeResetResultDto.class);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("accountsReset", result == null ? 0 : result.accountsReset());
      body.put("holderStashesZeroed", result == null ? 0 : result.holderStashesZeroed());
      return ResponseEntity.ok(body);
    } catch (BackendServiceException e) {
      log.debug("Bank wipe reset (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Bank wipe reset (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
