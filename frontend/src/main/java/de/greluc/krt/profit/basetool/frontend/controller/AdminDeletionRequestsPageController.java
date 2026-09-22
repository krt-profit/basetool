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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The admin queue for the members' Art. 17 erasure requests ({@code /admin/deletion-requests},
 * REQ-SEC-061).
 *
 * <p>Modelled on the Discord-registration queue, deliberately: an admin who has decided one of
 * these should not have to learn a second interaction pattern for the other.
 *
 * <p><b>The queue is ordered oldest first, and that is not cosmetic.</b> Art. 12(3) gives the
 * controller one month to respond to a data-subject request, so the top of the list is the one
 * closest to a deadline. The {@code DeletionRequestOverdue} alert watches the same number.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/deletion-requests")
@RequiredArgsConstructor
@Slf4j
public class AdminDeletionRequestsPageController {

  private static final ParameterizedTypeReference<List<AdminDeletionRequestDto>> REQUEST_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the queue.
   *
   * <p>A backend failure renders the page with an error banner and an empty list rather than an
   * error page: the admin area's other queues behave the same way, and a half-loaded admin page is
   * more useful than none.
   *
   * @param model the view model
   * @return the view name
   */
  @NotNull
  @GetMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String page(Model model) {
    try {
      model.addAttribute(
          "requests", backendApiClient.get("/api/v1/admin/deletion-requests", REQUEST_LIST_TYPE));
    } catch (Exception e) {
      log.error("Loading the deletion-request queue failed", e);
      model.addAttribute("requests", List.of());
      model.addAttribute("error", "admin.deletionRequests.error.load");
    }
    return "admin/deletion-requests";
  }

  /**
   * Re-renders the queue table as a fragment, for the in-place refresh after a decision
   * (REQ-FE-001).
   *
   * <p><b>A failure is re-thrown, not swallowed.</b> This used to catch and render an empty list,
   * which paints "Keine offenen Löschanträge" — telling the admin the Art. 12(3) queue is empty
   * when the backend is simply unreachable. The banner its {@code page()} sibling sets could not
   * have helped: it sits outside {@code th:fragment="rows"} and would never have rendered here.
   *
   * <p>Letting it propagate gives {@code krtFetch} a non-2xx to work with, so the client shows its
   * error toast and leaves the table it already has on screen. Stale-but-labelled beats
   * empty-and-confident on a queue with a statutory deadline.
   *
   * @param model the view model
   * @return the fragment view name
   */
  @NotNull
  @GetMapping(params = "fragment=rows")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String rows(@NotNull Model model) {
    model.addAttribute(
        "requests", backendApiClient.get("/api/v1/admin/deletion-requests", REQUEST_LIST_TYPE));
    return "admin/deletion-requests :: rows";
  }

  /**
   * Refuses a request. The reason is mandatory — Art. 12(4) requires the requester to be told it —
   * and is rejected here as well as by the backend and the database, so a client bug cannot produce
   * an unexplained refusal.
   *
   * @param id the request to refuse
   * @param request the client payload; {@code note} must be present and non-blank
   * @return {@code 200} on success, {@code 400} without a reason, else the relayed backend status
   */
  @ResponseBody
  @PostMapping(value = "/{id}/decline", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> decline(
      @PathVariable UUID id, @NotNull @RequestBody Map<String, Object> request) {
    Object note = request.get("note");
    if (!(note instanceof String text) || text.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("grantHistoryErasure", false);
    body.put("note", text);
    body.put("version", request.get("version"));
    return relay(
        log,
        "declining deletion request " + id,
        () -> {
          backendApiClient.post(
              "/api/v1/admin/deletion-requests/" + id + "/decline", body, Object.class);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Carries a request out. Irreversible: the local row and the Keycloak account both go, and with
   * {@code grantHistoryErasure} the member's surviving handle snapshots are anonymised first.
   *
   * <p>The flag is read from the payload rather than from the request row, because it is the
   * <b>admin's</b> answer to the member's wish and not the wish itself — a wish is not an
   * instruction (decision 6, {@literal @}greluc).
   *
   * @param id the request to carry out
   * @param request the client payload; only {@code grantHistoryErasure} is read
   * @return {@code 200} on success, else the relayed backend status
   */
  @ResponseBody
  @PostMapping(value = "/{id}/execute", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Object> execute(
      @PathVariable UUID id, @RequestBody Map<String, Object> request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("grantHistoryErasure", Boolean.TRUE.equals(request.get("grantHistoryErasure")));
    // Relayed verbatim, including null. The backend reads null as the admin force-save the
    // OptimisticLock helper family exists for; coercing it to a number here would invent a claim
    // about the row's state that the client did not make (REQ-FE-003).
    body.put("version", request.get("version"));
    // No note is relayed. An execution has nowhere to record one -- the deletion_request row
    // cascades away with the account, and REQ-AUDIT-001 keeps free text out of the audit payload --
    // so the dialog no longer asks for one either. Collecting a justification and discarding it is
    // worse than not collecting it: the admin believes they have recorded their reasoning.
    // A refusal is the case where it survives, and /decline requires it.
    return relay(
        log,
        "executing deletion request " + id,
        () -> {
          backendApiClient.post(
              "/api/v1/admin/deletion-requests/" + id + "/execute", body, Object.class);
          return ResponseEntity.ok().build();
        });
  }
}
