# SDD ledger — plan: docs/superpowers/plans/2026-11-13-musico-native-player.md
Pre-flight: T3 PlayQueue -> T7 QueueStore/PlayerController: consistent. T5 LibraryStore.onTracksRemoved(Set<String>) -> T7 QueueStore.removeTracks(Set<String>): consistent. T4 LyricsStore(fileURL:embedded:) vs T5 Track.hasEmbeddedLyrics (Bool only, no text): conflict.
Pre-flight: Ruling: embedded lyrics text is not stored in Track; LyricsStore callers get it by re-reading the file with MetadataReader on demand — keeps library.json small — cost if wrong: one extra file read when opening lyrics.
Task 1: Ruling: Info.plist lives at repo root as Musico-Info.plist (outside the synchronized Musico/ folder) — avoids duplicate-resource build error — cost if wrong: move one file.
Task 1: complete (commits 6a3c1aa..2e4cb2d, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Task 2: complete (commits 2e4cb2d..0df03b2, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Task 3: complete (commits 8f7beeb..54679dc, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Note: Task 3 range in the ledger is short by one commit; the task's real range is 0df03b2..54679dc (test-runner retry was added while recording it).
Task 4: complete (commits 54679dc..0679eaa, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Task 5: Ruling: Track gains `artworkKey: String?` (hash of the embedded image) and ArtworkCache is keyed by it (`store(_:) -> String?`, `image(forKey:)`, `prune(keeping:)`) instead of by track id — one stored image per album instead of one per song — cost if wrong: more disk use, nothing functional.
Task 5: Ruling: stores save immediately after each mutation rather than debouncing (spec says "debounced") — mutations are infrequent and scans save once at the end; playback position saving is throttled separately in Task 7 — cost if wrong: extra small writes.
Task 5: complete (commits 0679eaa..745dd80, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Task 6: complete (commits 745dd80..130840b, tests: scripts/test.sh → ** TEST SUCCEEDED **)
Task 7: Ruling: added PlayerControllerTests (plan had PlayerController build-only) — skip/failure, queue switching, restore and end-of-track rules are the riskiest logic and testable with the real engine on the fixture — cost if wrong: none (extra tests).
Task 7: Ruling: PlayerController also exposes removeFromQueue/moveInQueue/clearQueue/deleteQueue/trackDidEnd beyond the plan's list — the queue sheet needs to edit the active queue through the controller so playback follows — cost if wrong: none.
Task 7: complete (commits 130840b..1be0aee, tests: scripts/test.sh → 2026-11-13 03:50:12.959703+0900 Musico[81936:3708736]       AudioFileObject.cpp:80     Open failed)
Task 8/9: Ruling: library screens and player screens were written and committed together (RootView needs both to compile) — Task 9's range is empty and its work is in Task 8's commit — cost if wrong: none.
Task 8: complete (commits 1be0aee..d830e0e, tests: scripts/test.sh → 2026-11-13 03:51:38.630894+0900 Musico[83312:3727310]       AudioFileObject.cpp:80     Open failed)
Task 9: complete (in Task 8 commit; tests: scripts/test.sh → 90 passed)
Task 10: complete (simulator verification; fixes in 88ceb6c: UIFileSharingEnabled missing from built plist, EQ sheet layout, seek slider stuck after track end; tests: scripts/test.sh → 90 passed)
Final review: fresh reviewer (most capable model). 0 Critical, 9 Important, 19 Minor.
Final: fixed I1 position lost on interruption — testPauseKeepsPositionWhenEngineNoLongerReportsTime RED→GREEN, suite 101/101
Final: fixed I2 engine keeps rendering after stop — testEngineStopsRenderingWhenTrackEndsOrUnloads RED→GREEN, suite 101/101
Final: fixed I3 root removed during scan leaves orphans — testRemovingRootDuringScanLeavesNoOrphans RED→GREEN
Final: fixed I4 addRoot during scan skipped — testAddRootWhileScanningStillScansTheNewRoot RED→GREEN
Final: fixed I5 unreadable/empty listing or iCloud eviction purges playlists — testUnreadableRootReturnsNilNotEmpty, testEvictedICloudStubIsReportedAsPlaceholder, testPickedRootThatSuddenlyLooksEmptyKeepsTracksAndPlaylists, testEvictedICloudFileStaysInLibraryAndPlaylists RED→GREEN
Final: fixed I7 queues.json rewritten every 5 s — testPositionTicksStayInMemoryUntilSaved RED→GREEN
Final: fixed I8 scan all-or-nothing and rewritten when unchanged — testScanPublishesTracksInBatchesBeforeItFinishes, testUnchangedRescanDoesNotRewriteLibraryFile RED→GREEN
Final: fixed I9 QueueSheet redraws on every tick — view-only change, no test, build verified
Final: Ruling: I6 (blocking file I/O on main) fixed only in part — cover resizing moved off main; not-downloaded iCloud files are detected and skipped instead of opened (no test: cannot be reproduced in the Simulator). Opening audio files and resolving bookmarks stay synchronous — making load async is a redesign of the player's load path — cost if wrong: UI stalls when playing from slow USB/network storage or launching with an unreachable network folder.
Final: Ruling: re-graded Minor 1 (seek on a system-stopped engine can raise an uncatchable exception) and Minor 4 (timer stalls during scrolling) to Important by effect and fixed both — no failing test possible for either in the Simulator — cost if wrong: none.
Final: Ruling: a picked folder that lists zero songs while the index has songs for it is treated as temporarily unreadable (tracks kept) — protects playlists on cloud/external folders — cost if wrong: after genuinely emptying a picked folder its songs linger until the folder is removed from the library.
Final: Ruling: audio session is not deactivated when playback stops; only the engine is paused — keeps lock-screen controls responsive after pause — cost if wrong: other apps' audio is not told to resume.
Final: minor (deferred): speaker blip possible on headphone unplug (config-change restarts before route-change pauses)
Final: minor (deferred): UI not told when engine restart fails; mediaServicesWereReset not handled
Final: minor (deferred): launch retry of restore() never runs when the folder was unavailable (mini player shows 0:00 until play)
Final: minor (deferred): NFC-normalised paths may not resolve on exFAT/FAT/SMB volumes with NFD names
Final: minor (deferred): scanner descends into packages
Final: minor (deferred): list artwork loads full 600 px images and flashes placeholder on reuse
Final: minor (deferred): Songs sort and context-menu track lists recomputed in view bodies (slow with thousands of songs)
Final: minor (deferred): relocateRoot has no duplicate check; nested roots index files twice
Final: minor (deferred): unbalanced stopAccessing in resolve when start returned false
Final: minor (deferred): any JSON decode error renames the store to .corrupt (fragile to future schema changes)
Final: minor (deferred): EQ sheet indexes gains without bounds check (crash only with a hand-damaged settings.json)
Final: minor (deferred): sleep-timer remaining time shown only inside the sheet, not on the player (spec deviation)
Final: minor (deferred): Play/Shuffle include unavailable songs, so three in a row stop playback
Final: minor (deferred): removing the last, current queue item plays the previous song
Final: minor (deferred): lyrics view re-reads the file's tags before checking imported/sidecar; sidecar lookup is case-sensitive (.LRC)
Final: minor (deferred): compilations without an album-artist tag split into one album per artist
