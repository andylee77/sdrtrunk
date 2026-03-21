"""
Phase 4 Refactor: Remove broadcast(tracker) from TCM traffic-side methods.

CSM is now the sole authority for Events tab broadcasting.
TCM traffic methods keep tracker management + CSM forwarding but stop broadcasting.
"""
import re

TCM_PATH = 'src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java'
CSM_PATH = 'src/main/java/io/github/dsheirer/module/decode/p25/session/P25CallSessionManager.java'

def patch_tcm():
    with open(TCM_PATH, 'r') as f:
        content = f.read()

    original = content

    # ==== 1. Update broadcast(DecodeEvent) javadoc ====
    content = content.replace(
        '     * Broadcasts an initial or update decode event to any registered listener.\n'
        '     * Note: Phase 3 removed the passive observer call to mCallSessionManager.onDecodeEvent().\n'
        '     * The call session manager now receives events directly via processChannelGrant/Update\n'
        '     * and onTrafficChannelUpdate/End.\n',
        '     * Broadcasts an initial or update decode event to any registered listener.\n'
        '     *\n'
        '     * Phase 4: This method is only used for non-traffic-channel events (data channel grants,\n'
        '     * TrafficChannelTeardownMonitor rejected notifications). All traffic-side voice events\n'
        '     * are now broadcast exclusively by P25CallSessionManager via cached control events.\n'
    )

    # ==== 2. processP2TrafficCallEnd: remove broadcast(tracker) ====
    content = content.replace(
        '            //If we have a tracker that we can mark complete, broadcast the updated tracker/event.\n'
        '            if(tracker != null && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '                broadcast(tracker);\n'
        '            }',
        '            //If we have a tracker that we can mark complete, update it.\n'
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab via cached control events.\n'
        '            if(tracker != null && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '            }'
    )

    # ==== 3. processP2TrafficEndPushToTalk: remove broadcast(tracker) ====
    content = content.replace(
        '            //If we have a tracker that is started that we can mark complete, broadcast the updated tracker/event.\n'
        '            if(tracker != null && tracker.isStarted() && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '                broadcast(tracker);\n'
        '            }',
        '            //If we have a tracker that is started that we can mark complete, update it.\n'
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab via cached control events.\n'
        '            if(tracker != null && tracker.isStarted() && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '            }'
    )

    # ==== 4. processP2TrafficCurrentUser (single identifier): remove broadcast(tracker) ====
    content = content.replace(
        '                tracker.addIdentifierIfMissing(identifier);\n'
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                broadcast(tracker);\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }',
        '                tracker.addIdentifierIfMissing(identifier);\n'
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }'
    )

    # ==== 5. processP2TrafficVoice: remove broadcast(tracker) ====
    content = content.replace(
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                broadcast(tracker);\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }',
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }'
    )

    # ==== 6. processP2TrafficCurrentUser (IC overload): remove broadcast(tracker) + add CSM forwarding ====
    # First broadcast(tracker) in same-call branch
    content = content.replace(
        '                tracker.addDetailsIfMissing(additionalDetails);\n'
        '                tracker.addChannelDescriptorIfMissing(channelDescriptor);\n'
        '                broadcast(tracker);\n'
        '                return tracker.getEvent().getChannelDescriptor();',
        '                tracker.addDetailsIfMissing(additionalDetails);\n'
        '                tracker.addChannelDescriptorIfMissing(channelDescriptor);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }\n'
        '                return tracker.getEvent().getChannelDescriptor();'
    )
    # Second broadcast(tracker) in new-call branch
    content = content.replace(
        '            tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '            addTracker(tracker, frequency, timeslot);\n'
        '            broadcast(tracker);\n'
        '            return null;',
        '            tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '            addTracker(tracker, frequency, timeslot);\n'
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '            // Forward to call session manager\n'
        '            if(mCallSessionManager != null)\n'
        '            {\n'
        '                mCallSessionManager.onTrafficChannelUpdate(frequency, timeslot, ic, timestamp);\n'
        '            }\n'
        '            return null;'
    )

    # ==== 7. processP1TrafficCallStart: remove broadcast(tracker) ====
    content = content.replace(
        '            broadcast(tracker);\n'
        '\n'
        '            // Forward to call session manager\n'
        '            if(mCallSessionManager != null && tracker != null)\n'
        '            {\n'
        '                mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                        tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '            }',
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '            // Forward to call session manager\n'
        '            if(mCallSessionManager != null && tracker != null)\n'
        '            {\n'
        '                mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                        tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '            }'
    )

    # ==== 8. processP1TrafficCurrentUser (single identifier): remove broadcast(tracker) ====
    content = content.replace(
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                broadcast(tracker);\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }',
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }'
    )

    # ==== 9. processP1TrafficLDU1: remove both broadcast(tracker) calls ====
    # First one (inside if tracker != null block)
    # This pattern is unique because it has the LDU1 identifier loop before it
    content = content.replace(
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                broadcast(tracker);\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }\n'
        '            }\n'
        '            else\n'
        '            {\n'
        '                MutableIdentifierCollection mic = new MutableIdentifierCollection(identifiers);',
        '                tracker.updateDurationTraffic(timestamp);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }\n'
        '            }\n'
        '            else\n'
        '            {\n'
        '                MutableIdentifierCollection mic = new MutableIdentifierCollection(identifiers);'
    )

    # Second one (inside else block, new tracker creation)
    content = content.replace(
        '                    tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '                    addTracker(tracker, frequency, P25P1Message.TIMESLOT_1);\n'
        '                    broadcast(tracker);\n'
        '\n'
        '                    // Forward to call session manager\n'
        '                    if(mCallSessionManager != null)\n'
        '                    {\n'
        '                        mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, mic, timestamp);\n'
        '                    }',
        '                    tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '                    addTracker(tracker, frequency, P25P1Message.TIMESLOT_1);\n'
        '                    //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                    // Forward to call session manager\n'
        '                    if(mCallSessionManager != null)\n'
        '                    {\n'
        '                        mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, mic, timestamp);\n'
        '                    }'
    )

    # ==== 10. processP1TrafficCurrentUser (IC overload): remove both broadcast(tracker) calls ====
    # First one (same-call branch) — already handled by pattern #8 above, but check for second occurrence
    # Actually #8 matched the single-id version. The IC overload has additionalDetails:
    content = content.replace(
        '                tracker.addDetailsIfMissing(additionalDetails);\n'
        '                broadcast(tracker);\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }\n'
        '                return;',
        '                tracker.addDetailsIfMissing(additionalDetails);\n'
        '                //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '                // Forward to call session manager\n'
        '                if(mCallSessionManager != null)\n'
        '                {\n'
        '                    mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1,\n'
        '                            tracker.getEvent().getIdentifierCollection(), timestamp);\n'
        '                }\n'
        '                return;'
    )
    # Second one (new-call branch)
    content = content.replace(
        '            tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '            addTracker(tracker, frequency, P25P1Message.TIMESLOT_1);\n'
        '            broadcast(tracker);\n'
        '\n'
        '            // Forward to call session manager\n'
        '            if(mCallSessionManager != null)\n'
        '            {\n'
        '                mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, ic, timestamp);\n'
        '            }',
        '            tracker = new P25TrafficChannelEventTracker(callEvent);\n'
        '            addTracker(tracker, frequency, P25P1Message.TIMESLOT_1);\n'
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab.\n'
        '\n'
        '            // Forward to call session manager\n'
        '            if(mCallSessionManager != null)\n'
        '            {\n'
        '                mCallSessionManager.onTrafficChannelUpdate(frequency, P25P1Message.TIMESLOT_1, ic, timestamp);\n'
        '            }'
    )

    # ==== 11. processP1TrafficCallEnd: remove broadcast(tracker) ====
    content = content.replace(
        '            //If we have a tracker that we can mark complete, broadcast the updated tracker/event.\n'
        '            if(tracker != null && tracker.isStarted() && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '                broadcast(tracker);\n'
        '            }',
        '            //If we have a tracker that we can mark complete, update it.\n'
        '            //Phase 4: no longer broadcast tracker — CSM handles Events tab via cached control events.\n'
        '            if(tracker != null && tracker.isStarted() && tracker.completeTraffic(timestamp))\n'
        '            {\n'
        '                completed = true;\n'
        '            }'
    )

    if content == original:
        print("WARNING: No changes made to TCM!")
        return False

    remaining = content.count('broadcast(tracker)')
    print(f"TCM: broadcast(tracker) calls remaining: {remaining}")

    with open(TCM_PATH, 'w') as f:
        f.write(content)

    print("TCM patched successfully")
    return True


def patch_csm():
    with open(CSM_PATH, 'r') as f:
        content = f.read()

    original = content

    # In onTrafficChannelUpdate(), after all session/event updates, always re-broadcast
    # the cached control event. Currently it only broadcasts on encryption upgrades.
    # We need to add a general broadcast at the end of the method, after the currentEvent update.

    # Find the end of onTrafficChannelUpdate — the section after updating currentEvent
    old_block = (
        '            CallSessionEvent currentEvent = session.getCurrentEvent();\n'
        '            if(currentEvent != null)\n'
        '            {\n'
        '                currentEvent.updateEnd(timestamp);\n'
        '\n'
        '                // Update FROM radio if the traffic channel provides one\n'
        '                if(fromRadio != null)\n'
        '                {\n'
        '                    currentEvent.setFromRadio(fromRadio);\n'
        '                }\n'
        '\n'
        '                // Only update the identifier collection if it has richer info\n'
        '                // (has a FROM radio) to avoid overwriting an identified FROM with null\n'
        '                if(ic != null)\n'
        '                {\n'
        '                    if(fromRadio != null || currentEvent.getFromRadio() == null)\n'
        '                    {\n'
        '                        currentEvent.setIdentifierCollection(ic);\n'
        '                    }\n'
        '                }\n'
        '                notifyEventUpdated(session, currentEvent);\n'
        '            }\n'
        '        }\n'
        '        catch(Exception e)\n'
        '        {\n'
        '            mLog.error("Error processing traffic channel update", e);\n'
        '        }'
    )

    new_block = (
        '            CallSessionEvent currentEvent = session.getCurrentEvent();\n'
        '            if(currentEvent != null)\n'
        '            {\n'
        '                currentEvent.updateEnd(timestamp);\n'
        '\n'
        '                // Update FROM radio if the traffic channel provides one\n'
        '                if(fromRadio != null)\n'
        '                {\n'
        '                    currentEvent.setFromRadio(fromRadio);\n'
        '                }\n'
        '\n'
        '                // Only update the identifier collection if it has richer info\n'
        '                // (has a FROM radio) to avoid overwriting an identified FROM with null\n'
        '                if(ic != null)\n'
        '                {\n'
        '                    if(fromRadio != null || currentEvent.getFromRadio() == null)\n'
        '                    {\n'
        '                        currentEvent.setIdentifierCollection(ic);\n'
        '                    }\n'
        '                }\n'
        '                notifyEventUpdated(session, currentEvent);\n'
        '            }\n'
        '\n'
        '            // Phase 4: Always re-broadcast the cached control event to the Events tab.\n'
        '            // Since TCM no longer broadcasts traffic-side events, CSM must update the\n'
        '            // Events tab with duration, identifiers, and any other traffic-channel info.\n'
        '            broadcastTrafficUpdate(frequency, timeslot, ic, timestamp);\n'
        '        }\n'
        '        catch(Exception e)\n'
        '        {\n'
        '            mLog.error("Error processing traffic channel update", e);\n'
        '        }'
    )

    content = content.replace(old_block, new_block)

    # Now add the broadcastTrafficUpdate helper method after broadcastControlGrantEvent
    insert_after = '    // ========================================================================\n    // Phase 3: Shared Logic (moved from P25TrafficChannelManager)\n    // ========================================================================'

    new_method = (
        '\n\n'
        '    /**\n'
        '     * Phase 4: Re-broadcasts the cached control event to the Events tab with updated info\n'
        '     * from traffic channel messages (duration, identifiers, encryption).\n'
        '     *\n'
        '     * Since TCM no longer broadcasts traffic-side events directly, this method ensures the\n'
        '     * Events tab stays current with traffic channel activity. The cached control event\n'
        '     * (created by broadcastControlGrantEvent during the initial grant) is updated in-place\n'
        '     * and re-broadcast, preserving ClearableHistoryModel object identity for in-row updates.\n'
        '     */\n'
        '    private void broadcastTrafficUpdate(long frequency, int timeslot, IdentifierCollection ic, long timestamp)\n'
        '    {\n'
        '        if(mDecodeEventListener == null)\n'
        '        {\n'
        '            return;\n'
        '        }\n'
        '\n'
        '        // Try the exact key first, then fallback for Phase 1 (timeslot mismatch: control=0, traffic=1)\n'
        '        String eventKey = frequency + ":" + timeslot;\n'
        '        P25ChannelGrantEvent existing = mActiveControlEvents.get(eventKey);\n'
        '        if(existing == null && timeslot != 0)\n'
        '        {\n'
        '            eventKey = frequency + ":0";\n'
        '            existing = mActiveControlEvents.get(eventKey);\n'
        '        }\n'
        '\n'
        '        if(existing != null)\n'
        '        {\n'
        '            existing.setDuration(timestamp - existing.getTimeStart());\n'
        '            if(ic != null)\n'
        '            {\n'
        '                existing.setIdentifierCollection(ic);\n'
        '            }\n'
        '            mDecodeEventListener.receive(existing);\n'
        '        }\n'
        '    }\n'
    )

    content = content.replace(insert_after, insert_after + new_method)

    # Add import for P25ChannelGrantEvent if not already present
    if 'import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;' not in content:
        content = content.replace(
            'import io.github.dsheirer.module.decode.event.DecodeEvent;',
            'import io.github.dsheirer.module.decode.event.DecodeEvent;\nimport io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;'
        )

    if content == original:
        print("WARNING: No changes made to CSM!")
        return False

    with open(CSM_PATH, 'w') as f:
        f.write(content)

    print("CSM patched successfully")
    return True


if __name__ == '__main__':
    tcm_ok = patch_tcm()
    csm_ok = patch_csm()
    if tcm_ok and csm_ok:
        print("\nPhase 4 patch applied successfully!")
    else:
        print("\nWARNING: Some patches may not have applied correctly!")
