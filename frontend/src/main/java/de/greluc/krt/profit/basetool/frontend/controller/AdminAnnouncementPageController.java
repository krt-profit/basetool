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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin announcement-management page ({@code /admin/announcement}).
 *
 * <p>The announcement is a single shared record across the squadron — the page reads it via the
 * {@code /admin} endpoint (returns the record even when no public announcement is currently
 * published) and exposes Create/Update/Delete actions. PUT carries an optimistic-lock version so a
 * second admin editing the same announcement concurrently sees a 409 toast rather than silently
 * overwriting the other person's text.
 */
@Controller
@RequestMapping("/admin/announcement")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminAnnouncementPageController {

  /** Response type for the raw admin-view announcement record ({@code Map<String, Object>}). */
  private static final ParameterizedTypeReference<Map<String, Object>> STRING_OBJECT_MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Loads the current admin-view announcement record. A backend failure is logged but the page
   * still renders so the admin can post a new announcement from the empty form.
   *
   * @param model Thymeleaf model populated with {@code adminAnnouncement} (raw JSON map)
   * @return the {@code admin/announcement} view name
   */
  @GetMapping
  public String showAnnouncementPage(Model model) {
    try {
      Map<String, Object> adminAnnouncement =
          backendApiClient.get("/api/v1/announcement/admin", STRING_OBJECT_MAP_TYPE);
      model.addAttribute("adminAnnouncement", adminAnnouncement);
    } catch (Exception e) {
      log.error("Could not fetch admin announcement", e);
    }
    return "admin/announcement";
  }

  /**
   * Updates the shared announcement.
   *
   * <p>{@code version} is optional ({@code null} = first-time create); a 409 with problem type
   * {@code concurrency-conflict} surfaces as a dedicated optimistic-lock toast.
   *
   * @param content new announcement text (raw)
   * @param version optimistic-lock version, may be {@code null} on first creation
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/announcement}
   */
  @PostMapping("/update")
  public String updateAnnouncement(
      @RequestParam String content,
      @RequestParam(required = false) Long version,
      RedirectAttributes redirectAttributes) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("content", content);
      body.put("version", version);

      backendApiClient.put("/api/v1/announcement", body, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      log.debug("Update announcement failed", e);
      if (e.getStatusCode() == 409 && "concurrency-conflict".equals(e.getProblemType())) {
        redirectAttributes.addFlashAttribute("errorToast", "error.concurrency.conflict");
      } else {
        redirectAttributes.addFlashAttribute("errorToast", "error.profile.update.failed");
      }
      return "redirect:/admin/announcement";
    } catch (Exception e) {
      log.error("Update announcement failed", e);
      return "redirect:/admin/announcement?error=UpdateFailed";
    }
    return "redirect:/admin/announcement";
  }

  /**
   * In-place (AJAX) twin of {@link #updateAnnouncement} — routed here ahead of the classic handler
   * by the {@code X-Requested-With} header so the no-JS form keeps its redirect fallback.
   *
   * <p>Persists the new text, then re-reads the admin record to obtain the bumped optimistic-lock
   * version and returns it as {@code {"version": <n>}} so the page can write it back into the
   * hidden version input (the next edit would otherwise 409). A backend conflict is relayed
   * verbatim as {@code application/problem+json} (carrying the {@code OPTIMISTIC_LOCK} code) so the
   * shared {@code krtFetch} client shows the reload-confirm prompt instead of a full reload.
   *
   * @param request JSON body with {@code content} (required) and optional {@code version}
   * @return {@code 200 {"version": <n>}} on success, the relayed backend status on
   *     conflict/failure, {@code 400} when {@code content} is missing, {@code 500} on an unexpected
   *     error
   */
  @ResponseBody
  @PostMapping(value = "/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateAnnouncementAjax(@RequestBody Map<String, Object> request) {
    Object contentValue = request.get("content");
    if (!(contentValue instanceof String content)) {
      return ResponseEntity.badRequest().build();
    }
    Object versionValue = request.get("version");
    Long version = versionValue instanceof Number number ? number.longValue() : null;
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("content", content);
      body.put("version", version);
      backendApiClient.put("/api/v1/announcement", body, Void.class);

      Map<String, Object> updated =
          backendApiClient.get("/api/v1/announcement/admin", STRING_OBJECT_MAP_TYPE);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("version", updated != null ? updated.get("version") : null);
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Update announcement (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update announcement (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Removes the current announcement entirely. Failure redirects with an error query param.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/announcement} (optionally with {@code ?error=...})
   */
  @PostMapping("/delete")
  public String deleteAnnouncement(RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/announcement", Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete announcement failed", e);
      return "redirect:/admin/announcement?error=DeleteFailed";
    }
    return "redirect:/admin/announcement";
  }

  /**
   * In-place (AJAX) twin of {@link #deleteAnnouncement}. On success the page clears its form in
   * place (empty text, version reset to the create state) rather than reloading; a backend failure
   * is relayed so the client surfaces an inline toast.
   *
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteAnnouncementAjax() {
    try {
      backendApiClient.delete("/api/v1/announcement", Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Delete announcement (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Delete announcement (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
