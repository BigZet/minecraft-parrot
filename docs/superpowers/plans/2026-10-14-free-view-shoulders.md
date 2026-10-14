# Free flight, viewing and shoulders

**Goal:** Twitch viewers can toggle looking at the streamer, freely wander, wander near the streamer, and sit on or leave the streamer's shoulders.

**Architecture:** Extend the existing command API and behavior state machine. Reuse vanilla flight/look controls and actual player shoulder NBT, preserving the existing bird UUID and marker. No new persistence layer or dependencies.

**Spec:** User request of 14 October 2026 and `viewer_parrots_technical_spec.md`.

## Constraints

- Hobby singleplayer Fabric 1.21.1 mod; existing dirty checkout stays intact.
- Chat commands act on the sender's own bird through the existing bounded queue.
- One final assemble via `scripts/verify.ps1`, relevant tests and a short game scenario.
- No production/server audit, commits, or token changes for this feature.

## Work

- [x] Extend routing with VIEW/DEVIEW, ARM/DEARM, FREE/FREEFOLLOW. Looking is an independent saved entity toggle. New commands use a two-second per-viewer cooldown.
- [x] Add stable FREE and FREEFOLLOW intent. FREE uses vanilla-style flights with pauses; FREEFOLLOW stays roughly within 12 blocks of the streamer and catches up when needed. New birds default to FREE; legacy IDLE remains compatible.
- [x] Add head tracking using vanilla LookControl, with a 75-degree head yaw limit and body rotation only beyond that limit while standing. Moving birds retain natural movement orientation.
- [x] Use the two actual vanilla shoulder slots. When both are occupied, ARM releases one other bird into FREEFOLLOW and fills that slot. DEARM and movement commands release only the caller's bird. Normal vanilla automatic shoulder release remains available.
- [x] Preserve identity, hats, viewing flag and speech while a bird is stored on a shoulder. Add client rendering for the custom entity type and resolve shoulder positions for speech.
- [x] Update concise command docs, assemble once, run focused behavior/parser tests, install the JAR and check new actions in the singleplayer world.

## Relevant checks

- View on/off preserves free/sit/follow intent; head yaw is bounded and body turns when needed.
- Temporary dance/come returns to the selected stable free mode.
- Shoulder release and replacement retain the same bird UUID; no second bird appears.
- Hat/speech on a shoulder still use that viewer's bird.
- FREE has idle pauses; FREEFOLLOW follows a moving streamer without continuous circling.

Completed: assemble passed, 197 focused unit tests passed, six commands exercised in the real numnerd Twitch chat. Third-bird shoulder replacement confirmed using two temporary test birds, which were cleaned up. Audio listening is pending optional user confirmation; the shoulder UUID/position paths are implemented.
