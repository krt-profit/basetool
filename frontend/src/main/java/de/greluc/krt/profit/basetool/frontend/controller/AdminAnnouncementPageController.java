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
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
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
 * Controller for the admin announcement page ({@code /admin/announcement}): reads the single shared
 * announcement and offers create, update and delete. Updates carry the optimistic-lock version.
 */
@Controller
@UsesLayoutModel
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
  @NotNull
  @GetMapping
  public String showAnnouncementPage(Model model) {
    try {
      Map<String, Object> adminAnnouncement =
          backendApiClient.get("/api/v1/announcement/admin", STRING_OBJECT_MAP_TYPE);
      model.addAttribute("adminAnnouncement", adminAnnouncement);
    } catch (BackendServiceException e) {
      log.debug("Could not fetch admin announcement", e);
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
  @NotNull
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
   * AJAX variant of {@link #updateAnnouncement}, selected by the {@code X-Requested-With} header.
   * Returns the bumped version so the page can update its hidden input; backend errors are relayed
   * as {@code application/problem+json}.
   *
   * @param request JSON body with {@code content} (required) and optional {@code version}
   * @return {@code 200 {"version": <n>}} on success, the relayed backend status on
   *     conflict/failure, {@code 400} when {@code content} is missing, {@code 500} on an unexpected
   *     error
   */
  @ResponseBody
  @PostMapping(value = "/update", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateAnnouncementAjax(
      @NotNull @RequestBody Map<String, Object> request) {
    Object contentValue = request.get("content");
    if (!(contentValue instanceof String content)) {
      return ResponseEntity.badRequest().build();
    }
    Object versionValue = request.get("version");
    Long version = versionValue instanceof Number number ? number.longValue() : null;
    return relay(
        log,
        "update announcement (ajax)",
        () -> {
          Map<String, Object> body = new HashMap<>();
          body.put("content", content);
          body.put("version", version);
          backendApiClient.put("/api/v1/announcement", body, Void.class);

          Map<String, Object> updated =
              backendApiClient.get("/api/v1/announcement/admin", STRING_OBJECT_MAP_TYPE);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("version", updated != null ? updated.get("version") : null);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Removes the current announcement entirely. Failure redirects with an error query param.
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/announcement} (optionally with {@code ?error=...})
   */
  @NotNull
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
   * AJAX variant of {@link #deleteAnnouncement}; backend failures are relayed for an inline toast.
   *
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteAnnouncementAjax() {
    return relay(
        log,
        "delete announcement (ajax)",
        () -> {
          backendApiClient.delete("/api/v1/announcement", Void.class);
          return ResponseEntity.ok().build();
        });
  }
}
