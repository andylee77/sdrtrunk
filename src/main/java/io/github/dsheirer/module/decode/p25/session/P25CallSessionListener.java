/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.session;

/**
 * Listener interface for P25 call session lifecycle events.
 *
 * Implementations can subscribe to a P25CallSessionManager to receive notifications
 * about session creation, per-talker events, and session completion.
 */
public interface P25CallSessionListener
{
    /**
     * Called when a new call session is created (channel grant received).
     * At this point the session may not yet have any events.
     *
     * @param session the newly created session
     */
    void onSessionCreated(P25CallSession session);

    /**
     * Called when a new per-talker event is added to an existing session.
     * This happens when a new FROM radio starts transmitting within a session,
     * or when the first event is created for a new session.
     *
     * @param session the parent session
     * @param event the newly added per-talker event
     */
    void onSessionEventAdded(P25CallSession session, P25CallSessionEvent event);

    /**
     * Called when an existing per-talker event is updated (duration extended, identifiers added).
     *
     * @param session the parent session
     * @param event the updated per-talker event
     */
    void onSessionEventUpdated(P25CallSession session, P25CallSessionEvent event);

    /**
     * Called when a session reaches COMPLETE state (gap threshold exceeded after ENDING).
     * All per-talker events are finalized. This is the trigger for writing to the database.
     *
     * @param session the completed session
     */
    void onSessionComplete(P25CallSession session);
}
