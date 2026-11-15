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

/** @typedef {ApiDto<'MissionTemplateDto'>} MissionTemplate */

/** The parts a mission template can set, in editor order. */
export const TEMPLATE_PARTS = /** @type {const} */ ([
    'description',
    'schedule',
    'meetingPoint',
    'visibility',
    'frequencies',
    'objectives',
    'steps',
    'units',
]);

/** @typedef {typeof TEMPLATE_PARTS[number]} TemplatePart */

/**
 * Tells whether a template sets a part; an absent value or an empty list leaves it to the planner.
 *
 * @param {Partial<MissionTemplate>} template the saved template or an editor draft
 * @param {TemplatePart} part the part to check
 * @returns {boolean} whether the template carries the part
 */
export function hasPart(template, part) {
    switch (part) {
        case 'description':
            return Boolean(template.description);
        case 'schedule':
            return Boolean(
                template.meetingTime || template.plannedStartTime || template.plannedEndTime,
            );
        case 'meetingPoint':
            return Boolean(template.meetingPoint);
        case 'visibility':
            return template.isInternal != null;
        case 'frequencies':
        case 'objectives':
        case 'steps':
        case 'units':
            return (template[part]?.length ?? 0) > 0;
    }
}

/**
 * Cuts an ISO time of day to the `HH:mm` a time input takes.
 *
 * @param {string | null | undefined} time the ISO time of day
 * @returns {string} the hours and minutes, or empty when absent
 */
export function timeOfDay(time) {
    return time ? time.slice(0, 5) : '';
}
