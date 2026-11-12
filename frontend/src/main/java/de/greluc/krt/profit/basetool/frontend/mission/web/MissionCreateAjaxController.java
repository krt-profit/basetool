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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.problem;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;
import static de.greluc.krt.profit.basetool.frontend.mission.web.MissionWriteValues.fieldErrors;

import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.RequestContextUtils;
import tools.jackson.core.JacksonException;

/** Handles the in-place mission create form submission. */
@RestController
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
class MissionCreateAjaxController {

  private final MissionCreation creation;
  private final MessageSource messageSource;

  /**
   * Creates a mission from the editable draft and reports validation or template conflicts in
   * place.
   *
   * @param form the submitted draft
   * @param bindingResult form validation results
   * @param locale the request locale
   * @param servletRequest the request carrying the success flash
   * @param servletResponse the response carrying the flash session
   * @return the detail navigation target or a typed refusal
   */
  @PostMapping("/missions/ajax")
  public ResponseEntity<Object> createMissionAjax(
      @Valid @ModelAttribute("missionForm") MissionForm form,
      BindingResult bindingResult,
      Locale locale,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    if (bindingResult.hasErrors()) {
      return ResponseEntity.unprocessableContent()
          .body(fieldErrors(bindingResult, messageSource, locale));
    }
    return relay(
        log,
        "create mission (ajax)",
        () -> {
          CreateMissionRequest request;
          try {
            request = creation.request(form);
          } catch (JacksonException | IllegalArgumentException _) {
            return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                messageSource.getMessage("error.mission.create", null, locale));
          }
          String target = creation.save(request);
          var flash = RequestContextUtils.getOutputFlashMap(servletRequest);
          if (flash != null) {
            flash.put("successToast", "notification.success.save");
            RequestContextUtils.saveOutputFlashMap(target, servletRequest, servletResponse);
          }
          return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("targetUrl", target));
        });
  }
}
