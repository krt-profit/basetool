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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;
import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionTemplateBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.CreateMissionTemplateRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionTemplateDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionTemplateRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Proxies mission template editor requests without building the layout model. */
@RestController
@RequestMapping("/mission-templates")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MissionTemplateAjaxController {

  private final MissionTemplateBackendClient templates;

  /**
   * Reads the latest template for the editor.
   *
   * @param id the template id
   * @return the current template
   */
  @GetMapping("/{id}")
  public MissionTemplateDto detail(@PathVariable UUID id) {
    return withBackendStatus(() -> templates.detail(id));
  }

  /**
   * Saves a new template with an explicit owner.
   *
   * @param body the owner and initial planning content
   * @return the saved template or backend refusal
   */
  @PostMapping
  public ResponseEntity<Object> create(@RequestBody CreateMissionTemplateRequest body) {
    try {
      return ResponseEntity.status(HttpStatus.CREATED).body(templates.create(body));
    } catch (BackendServiceException refusal) {
      return propagateBackendError(refusal);
    }
  }

  /**
   * Saves changes to the revision the editor loaded.
   *
   * @param id the template id
   * @param body the updated planning content and expected revision
   * @return the updated template or backend refusal
   */
  @PutMapping("/{id}")
  public ResponseEntity<Object> update(
      @PathVariable UUID id, @RequestBody UpdateMissionTemplateRequest body) {
    try {
      return ResponseEntity.ok(templates.update(id, body));
    } catch (BackendServiceException refusal) {
      return propagateBackendError(refusal);
    }
  }

  /**
   * Deletes the template at its displayed revision.
   *
   * @param id the template id
   * @param version the expected revision
   * @return an empty success or the backend refusal
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Object> delete(@PathVariable UUID id, @RequestParam long version) {
    try {
      templates.delete(id, version);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException refusal) {
      return propagateBackendError(refusal);
    }
  }
}
