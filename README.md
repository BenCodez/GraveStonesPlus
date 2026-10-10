# GraveStonesPlus

Get builds here: http://bencodez.com/job/GraveStonesPlus/

## Per-grave helpers

Owners use a selected grave from `/gravestonesplus graves`, or look at their marker:

- `/gravestonesplus trust <online-name|uuid> track <duration>` allows navigation only.
- `/gravestonesplus trust <online-name|uuid> recover <duration>` allows full recovery to the helper's inventory, including the grave's XP. RECOVER does not grant TRACK; grant both when both are wanted.
- `/gravestonesplus trustlist` lists active helper UUIDs, kinds, and deadlines.
- `/gravestonesplus revoketrust <online-name|uuid> <track|recover>` revokes one kind.

Durations must be positive and at most 90 days (for example `30m`, `2h`, `7d`). Online names avoid profile or network lookup in commands; offline players can be addressed by UUID. Grants use owner UUID, death time, world UUID and block coordinates, rather than names or marker entity IDs. New grants become active only after an atomic file publication is acknowledged. Revocation denies access immediately in this process, and reports success only after persistence. A failed revocation is **not** durable across restart; repair storage and retry before restarting.

Permissions: `GraveStonesPlus.Trust` and `GraveStonesPlus.Trust.Revoke` default to false; `GraveStonesPlus.Track` defaults to true. Existing Graves, Compass, ClaimGrave and staff menu permissions still apply. Owners and helpers are rechecked at the final action, including after claim/removal event callbacks. Existing `Breaking.Other` enablement, permission and protection-delay rules continue to authorize non-helper recovery independently; TRACK cannot bypass them. Staff menu authorities (`AllGraves`, `AllGraves.Player`, `AllBrokenGraves`, `AllBrokenGraves.Player`) retain their administrative actions.

## Navigation and recovery

`/gravestonesplus graves` opens the existing grave inventory with owned and TRACK-authorized graves. Left click selects a grave compass with `GraveStonesPlus.Compass`; right click requires `GraveStonesPlus.ClaimGrave` and recovers only within `GraveClaimDistance`; shift left views contents with recovery authority; shift right removes only as owner/staff. `/gravestonesplus track` selects the accessible marker being looked at and reports its navigation details without issuing an item; it requires `GraveStonesPlus.Track`. `/gravestonesplus compass` uses the selected target, falling back to the owner's latest grave. `/gravestonesplus claimgrave` also finds nearby RECOVER-authorized graves.

Every compass delivery, including menu clicks and `GiveCompassOnRespawn`, rechecks `GraveStonesPlus.Compass`, online state and current grave access on the player's entity owner immediately before inventory insertion. Tracking selection remains available when Compass is denied. Compass issuance does not queue an unchecked later inventory delivery. A full inventory follows AdvancedCore's existing `DropOnFullInv` policy: enabled drops the compass at the player; disabled asks the player to make room and request it again instead of retaining a pending compass. Captured grave contents continue to use the existing recovery overflow handling.

Navigation shows the target world, horizontal distance and above/below/level position. Cross-world targets are labeled rather than given a misleading distance. Selection and compass validation use stored identities and locations and never load chunks. Revocation, elapsed grants, expiry, claim and removal invalidate access; online compasses are refreshed by bounded periodic entity tasks and on login. Invalid grave compasses become ordinary inactive compasses. Legacy owner compasses are recognized by their death time and exact location. Staff menus retain teleport actions.

Recovery retains the existing configured `PercentageDrops` chance per stored stack; 100 skips RNG. Selected stacks restore every available original inventory/armor/offhand slot before displaced stacks are handled, then use free inventory space; overflow is handed to AdvancedCore's durable full-inventory delivery queue so `DropOnFullInv=false` never loses items (legacy direct delivery still drops when configured). The full stored XP goes to that recipient. Competing owner/helper/direct/menu attempts share one grave reservation and cannot pay the same record again. After all selected items and XP are acknowledged, a completed identity is archived before its active reservation is retired, preserving replay protection while allowing the bounded ledger to accept new graves. Overflow failure or recipient retirement keeps the reservation and exact recovery evidence for administrator reconciliation; successful recovery is not advertised while delivery is unacknowledged. Claim and removal cancellation occur before payout. Configured grave-limit ground payouts use the same reservation and chance rules, drop selected items plus the full XP at the grave on its region owner, and remove only after payout; cancelled or failed reservations preserve the grave. Unavailable chunks retain a durable `QUEUED_LIMIT` intent, retried through the existing restoration timer/chunk index after chunk load or restart without loading chunks. Each pending limit removal is skipped when selecting the next oldest grave, so repeated deaths do not repeatedly select the same unavailable grave. Queued requests cannot be claimed; only a persisted transition to `RESERVED` permits drops. If the chunk unloads before any drop, the request returns to queued state. Cancellation or ordinary manual/expiry removal retires an unstarted request; partial payouts remain reserved for administrator reconciliation. A recipient whose Folia scheduler retires before any payout has its reservation cancelled; expiration overlapping that reservation is retried when the claim releases.

## Reminders

Optional `Reminders.ProtectionMinutes` and `Reminders.ExpiryMinutes` default to 0, so warnings are opt-in; set either to 60 for a warning within the last hour. Zero disables the respective warning. Protection reminders require `Breaking.Other.Enabled` and a positive actual protection delay. Expiry reminders use the grave's scheduled expiration deadline, including legacy numeric minutes and duration strings; disabled and zero lifetimes have no expiry warning. Invalid or overflowing replacement lifetimes retain the current expiry deadline and timer; at initial creation there is no previous timer to retain. Reload reschedules the existing grave timers, so changed deadlines have distinct reminder identities.

Login sends one useful summary; eligible protection/expiry warnings are combined in that message. Periodic checks batch at most 16 online players per five-second pass, 256 owner graves per player pass (rotating through larger collections), and 64 new deadline warnings per summary. Delivered warnings are suppressed by recipient/grave/kind/deadline across restart. Players are checked and messaged on their entity owner; offline players are never marked. Publication capacity is reserved before sending, and suppression is persisted only after send acceptance. If persistence fails after delivery, local repeats are suppressed and storage warnings are logged; a restart can repeat that message because chat delivery and disk persistence are not atomic.

## Held-item inspection

`/gravestonesplus inspectitem` requires `GraveStonesPlus.InspectItem` (default false). It clones the held item and calls the same ordered evaluator as death capture: Ignore NBT, Slimefun soulbound, player-placeholder-expanded exact lore, Keep NBT, Grave NBT, Curse of Vanishing, then the default grave rule. It lists available NBT matches and metadata; NBT stages stay inactive when NBTAPI is absent. It describes later recovery/drop chance without sampling RNG. It does not mutate inventory, item metadata, drops, experience or a death event.

NBT routing lists and the configured keep-lore text publish as one immutable reload generation; death evaluation and each inspection retain a single generation across all rule stages. The routing prediction applies when grave creation proceeds. World, permissions, keep-inventory, combat, marker placement and other death hooks can affect that eligibility. Lore matching is case-insensitive but whole-line exact, including colors. The existing NBT matcher does not support list-index paths. Metadata is displayed as context; only the actual configured matcher/integrations determine routing.

### Integration API compatibility

`Grave.removeGrave(reason)` keeps its cancellable state transition synchronous when called on the Bukkit main thread; marker cleanup remains scheduled. Calls from other threads and all Folia calls hand off to the grave region. Persistence continues through the existing asynchronous storage owners.

`Grave.dropItemsOnGround(Player)` retains its item-only contract: it schedules a snapshot of chance-selected items at the grave location, leaves the grave and stored experience unchanged, and does not reserve or complete a recovery. The player argument remains optional and is not a payout recipient. Integrations using this method still own their removal/claim lifecycle; repeated explicit calls can drop items repeatedly, as before. Normal death/admin grave-limit handling uses the separate `dropAndRemoveForLimit(Player)` operation, whose durable reservation prevents duplicate removal payouts. Neither helper permissions nor player commands expose the item-only API.

## Storage and operational limits

Existing `Graves.yml`, MySQL schemas, configuration aliases and grave records remain unchanged. Selected feature state uses `selected-access.yml`, `selected-claims.yml`, `selected-claim-archive/` and `grave-reminders.yml`, with a strict acknowledged cache and atomic replacement and file sync, plus directory sync where the filesystem provider supports it. On the native Windows provider, Java cannot open a directory for this sync: the file is still forced before atomic replacement, but directory durability across an OS crash or power loss cannot be promised. Only that specific unsupported directory-open failure is tolerated; other publication failures leave the store unavailable until restart reconciliation. Keep these files alongside the existing data during upgrades or restores; deleting the claim ledger or its archive can re-enable old payouts. The initial uncommitted reminder `sent` list remains readable. The earlier experimental `trusted-graves.yml` was never a released format; new identity-bound grants use `selected-access.yml`.

Selected and gameplay-triggered backend writes use one private bounded serial I/O owner, with at most 129 admitted operations and a 129-slot physical queue (including a spare slot while the prior worker frame completes its drain). Limits are 4,096 active grants, 8,192 unresolved claim records and a 512-entry recent-completion lookup cache, 8,192 retained reminder identities, 4 MiB per feature file, and 256 KiB of captured evidence per claim. Expired grants and elapsed reminders reclaim space; replay guards are not silently evicted. Capacity, unreadable state, publication uncertainty, executor rejection and incomplete shutdown fail conservatively. A failed feature-file publication requires storage repair/restart reconciliation before further operations in that store. Rejected backend admissions retain one coalesced latest active/broken snapshot and flush it on the disk owner after older queued writes. Shutdown seals new admissions, honors existing unused publication tickets, and waits for accepted writes and that recovery pass to drain; slow storage can therefore delay shutdown. Interrupted waits restore interruption only after the owner has terminated. Overlapping initialization on the same plugin instance is refused.

Bukkit inventory/world/XP effects cannot be transactionally committed with files. Before payout, a durable `RESERVED` record stores recipient, exact slot contents and XP. A crash or partial payout leaves that record held for administrator reconciliation and **never automatically replays it**; `PAID` records also block administrative recreation for another payout. This prevents duplicate payment but is not an automatic exactly-once crash recovery guarantee. Ordinary startup/backend loading and the pre-existing death-capture failure policy remain separate from this claim journal; server-process failure during an unflushed death capture still needs backend/lifecycle work. These limits must be considered before publication.

## Verification

Use JDK 21+ and `mvn -B -f GraveStonesPlus/pom.xml test`, then `mvn -B -f GraveStonesPlus/pom.xml package`. Tests exercise production stores, claim/action and reminder entry points, delayed/failed persistence, restart suppression, access distinctions, deadlines, legacy grave shapes, overflow and the shared item evaluator. Bukkit/Folia ownership is checked through scheduler seams; this checkout does not provide a running Paper/Folia/MySQL integration server.

Completed claims are archived as one compact durable tombstone per grave identity before their active reservation is retired. The archive deliberately grows with recovered-grave history to preserve replay protection; no full directory scan or gameplay-thread disk lookup is performed. An evicted cache entry is checked asynchronously before another reservation or broken-grave recreation can succeed. Archived IDs remain blocked across restart or backend write failure, including administrative recreation of the same grave. Failed archive publication retains the existing reservation and cannot re-enable payout. Existing PAID journal entries are migrated on the I/O owner at initialization. Back up both the active journal and archive together; there is no automatic unsafe purge.

Startup restores at most 128 records per timer or chunk-load pass and admits one archive lookup/region callback at a time. Saturated archive admission leaves the remaining chunk identities indexed and releases the batch for a later retry. Per-identity decisions distinguish available, reserved, paid and unknown; admission failure, corrupt archive or unavailable storage leaves the backend record withheld and retries later. No restored marker metadata, navigation, hologram or expiration timer is exposed before its archive allows admission. Unresolved backend records are preserved when a partial startup or shutdown writes current lists. Bulk snapshots capture their unresolved-record union before queue admission, so later region admission cannot erase a restored record from an older queued write; a following removal snapshot remains authoritative. After archive admission, unloaded active graves remain visible to authorized owners/helpers and preserve compass identity; only native marker initialization waits for a chunk-load event or bounded retry. Pending archive decisions preserve existing compass targets without granting access. Startup and glow refresh do not force-load chunks.

`selected-marker-cleanup.yml` retains at most 8,192 removal identities with a per-chunk index, independently of broken-history eviction. Cleanup capacity remains reserved through durable publication acknowledgment, including a queued commit. Stored marker types use the same case/whitespace normalization and configuration fallback as live markers before reserving capacity; invalid fallback settings reject cleanup admission without retaining a disk reservation. Expiration retries transient cleanup admission pressure every five seconds with one current task; changed/disabled deadlines, removal and shutdown invalidate stale retries. Cleanup capacity is reserved before a removal or payout proceeds; unavailable/full cleanup storage rejects admission rather than dropping that work. New markers carry exact grave identity in tile, display/hologram and chunk PDC (including barriers). Cleanup runs only on the loaded region and removes only that identity, preserving a successor at the same coordinates. Untagged legacy markers without surviving exact metadata remain for conservative administrator reconciliation; no type/name guess deletes a replacement. Back up this file with grave storage. Confirmed intents retry after chunk load/restart without a world/chunk scan or forced load. Historical staff recreation and teleport use their historical permissions at the final actor-owned state mutation; region checks do not read the actor's permissions from another Folia region.

Removal and payout cleanup also unregister the grave's own top, middle, bottom and glow hologram wrappers from the bundled AdvancedCore handler. Replacement, glow retirement, nearby restoration cleanup and late entity creation use the same release path. Invalid glow refresh waits for the cancellable removal transition before releasing wrappers or entities. Registry release uses `remove(wrapper, false)` rather than AdvancedCore's global-sync `kill()` handoff; physical removal of those known stands is dispatched to each stand's entity owner. Durable identity cleanup remains responsible for unavailable/unloaded entities and persisted markers, without deleting another grave's registered wrappers or unrelated entities.

Startup reconciles surviving exact-identity hologram/display entities in the loaded grave region before creating replacements. Untagged legacy holograms cannot be safely distinguished from unrelated stands and require administrator reconciliation; they are never deleted by proximity or name alone.

Restoration preserves original world UUIDs when a saved world is unavailable; those records remain serializable alongside new graves and are retried when the world returns. An unavailable archive identity withholds that grave while other identities continue restoring. Invalid loaded markers still fire the cancellable `GraveRemoveEvent(INVALID)` before a durable transition, allowing integrations to cancel or repair the marker. Recreation also waits for durable removal of both helper grant kinds before changing a historical grave back to live, including after a failed or rejected removal write and restart. Queued grave-limit payouts restore their original lifetime before publication and recheck expiry after disk reservation and event callbacks; elapsed graves follow normal cancellable expiration without dropping queued contents. Invalid startup removal durably revokes both helper grant kinds before historical publication; failed revocation leaves that record withheld, and the final region callback rechecks the marker before removal. Startup cleanup waits for unresolved restoration identities. For tile markers, a stale positional chunk tag alone never authorizes deletion of an untagged replacement chest/head.

Nearby menu and command recovery recheck the claim permission, world and configured distance after durable reservation, before payout. Navigation menu rendering and click handling run on the viewer’s entity owner and recheck current live/access state after dispatch; distance uses one player-location snapshot, so a cross-world teleport cannot mix separate world and position reads. The nearby command skips inactive or ledger-blocked graves when selecting a target, so a retained uncertain payout cannot hide another eligible nearby grave. Historical records whose world is unavailable remain pending alongside active records until that world returns; one failed cleanup admission does not withhold following healthy records.

Upgrade restoration automatically reconciles surviving legacy hologram stands carrying GraveStonesPlus’s old integer tag at the exact native text/glow positions of a currently owned marker. Reconciliation runs on each entity owner and rechecks marker ownership; foreign, untagged, shifted, and successor-identity entities remain untouched. No manual cleanup is needed for native surviving legacy lines.

Persisted markers remain protected at their exact saved world/block position while archive or chunk restoration is pending, including native breaking, explosions, fluids, buckets, pistons, physics and block drops. Pending protection does not expose a claimable grave or grant access before archive admission. Destroyed, removed, moved, resolved and retired pending records cease providing that protection; material and successor identity checks keep unrelated blocks outside it. Delayed physics repairs recheck the original identity. Restoration's delayed entity cleanup preserves the visible display adopted by the live grave, including legacy displays and a replacement live instance, while removing stale owned holograms/displays.

Reload acknowledgements wait for asynchronous configuration reads and owner-side application. Java integrations that need completion should use the additive `reloadAsync()` future; the legacy `void reload()` wrapper starts the same work without waiting. A second reload is rejected while one is pending, and shutdown retires pending acknowledgements. A failed reload is reported as failure, not success; file reloads are not a cross-file transaction, so inspect the configuration before retrying after a partial read failure. Completion confirms owner-side application and admission of region-local timer checks, not completion of every timer callback.

Historical grave-menu actions capture click intent and perform permission/online/plugin checks on the acting player’s owner before recreation, item viewing or teleport dispatch. Delayed actions retain their existing final authorization checks.

Backend shutdown is a terminal request on the existing ordered feature worker. Accepted writes and retained recovery run before the backend closes once; an incomplete startup index is never published as an empty recovery snapshot. A permanently failing recovery logs failure and retains its in-memory snapshot, then closes without retrying against the closed backend. This is not durable preservation of an otherwise unwritable new snapshot across process termination; repair storage using the recorded grave/recovery data, and do not treat the diagnostic as a successful flush.

Historical recreation checks the administrator on the player’s owner and schedules marker and hologram world access separately on the saved grave location. Remote administrators do not need to stand in the grave’s Folia region. Location-only Java requests retain their location-scheduled path.

### Historical recreation persistence

Historical recreation moves the grave from the broken list to the active list only after the existing guarded
archive/access checks. Flat-file recovery publishes both sections of `Graves.yml` together through a checked
atomic replacement; the future-returning recreation API reports success after that write. Storage rejection or
failure is reported rather than acknowledged as saved. Recoverable in-memory state is retained for the existing
bounded storage recovery path, but unwritable storage cannot guarantee survival across process termination.
The legacy `Grave.createBrokenGrave()` still creates a marker and now uses the same list/persistence transition;
void recreation APIs remain asynchronous. MySQL recreation acknowledges one update to the exact existing row,
including restored destroyed/marker metadata; it leaves items and XP untouched. Missing rows, SQL errors and
uncommitted connections fail the acknowledgement. Existing YAML record keys and configuration are unchanged.

Pre-upgrade compasses with only the legacy time/lodestone tags retain their target while their exact saved grave
is awaiting restoration admission. Once that record is retired without admission, stale compass cleanup resumes.

Storage snapshots prepare encoded items before I/O admission. The worker reuses that captured payload for YAML/SQL and recovery; it never re-encodes the snapshot's Bukkit items. A preparation failure fails the write acknowledgement and retains repair state in memory, rather than publishing an empty inventory. Repair/retry must prepare a new snapshot on the owner; failed storage is not durable across a crash. Later captured removal state supersedes an older failed write. Existing persisted fields and schemas are unchanged.

For the default flat-file backend, startup on the server owner indexes the already-loaded record positions before protection listeners are registered; blocked claim/archive work does not delay that protection. Startup from other contexts hands preparation to the server owner, and protection begins when that handoff completes. In-memory record preparation and retries run on the server owner. MySQL continues to fetch records asynchronously: exact positions cannot be protected before the initial backend read completes. Ordinary blocks are never globally cancelled during that interval.

Java helper-access overloads taking owner UUID and death timestamp resolve a unique
location-bound grave identity from the active/historical index and canonical stored
grants. Missing or ambiguous references return no access and do not write or revoke
a grant. Prefer the full `GraveIdentity` overload when two graves share the same death
timestamp. The whole-grave compatibility revocation future reports unresolved identity
or persistence failure instead of silently acknowledging it. Shortened experimental
owner/time grant rows cannot establish a location and are never promoted into access;
existing command-created canonical rows continue to work across restart.

A restored grave first published while its chunk is unloaded is withheld again if
chunk admission observes a successor identity at that location. Its exact stored
record and helper grants remain retained, but navigation and recovery are unavailable;
the successor marker and data are untouched. Unloading the chunk again does not
clear that conflict. Only a loaded region proving the conflict gone can re-admit
the original record, using its original absolute expiry rather than extending time.

Historical restoration publishes broken records and prepares item snapshots on
an appropriate Bukkit owner after background archive persistence completes.
Remote staff recreation checks the actor's current authority on their entity
owner, then commits marker-related state on the grave's location owner. Graves
expired during startup admission are never republished as active. Navigation
refresh pauses whenever helper or claim state is unavailable, including uncertain
writes after a successful initial load, preserving compass metadata for recovery.

On Folia, grave chunk preparation loads on the location owner and retains a
six-second location-scheduler task. Folia owns that task's temporary region ticket;
the plugin does not read or change the world's persistent force-loaded flag from
a region thread. This keeps marker creation on its correct owner without leaving
a persistent force-load behind. Bukkit/Paper retain the existing force-load and
release behavior. This change does not make every legacy death/respawn path or
cross-region recovery scenario verified on Folia.

Reload reads bounded file contents on the I/O worker, then prepares general
settings, grave YAML and NBT rules on the Bukkit owner. All three publish as one
configuration generation only after preparation succeeds; a failed preparation
keeps the previous objects and rules. Reload uses the captured file contents rather
than rereading files during publication. The read limit is 16 MiB per file, in
addition to Bukkit's YAML parser limits. Legacy key migration still runs during
preparation but writes no file from the owner callback; those migrated keys persist
when the configuration is subsequently saved. Initial startup retains its existing
migration behavior. Backend selection and arbitrary AdvancedCore runtime side
effects are not a transactional storage switch; changing database settings still
requires a restart.

Delayed death notifications and kept-item respawn restoration use the moving
player's entity scheduler; marker creation stays on the grave's location scheduler.
Kept items are detached snapshots associated with one player and death generation;
successive deaths retain separate entitlements even when their items and slots are
identical. Pending snapshots are bounded to eight per player. If that bound is
full, the new death follows the existing no-grave failure policy and its drops and
experience remain untouched. Inventory insertion leftovers are dropped at the
owner's world location.
Repeated or stale respawn callbacks cannot consume a newer generation, and rejected,
retired, dead or offline player callbacks leave the pending snapshot untouched for a later
living respawn. A subsequent death fences older scheduled callbacks even when that
death is ineligible for a grave or captures no kept items; an undelivered snapshot is
retained rather than discarded solely to invalidate the callback. The existing five-tick compass delay is unchanged. These kept-item snapshots
remain session-local; this change does not add restart recovery for legacy death
capture or make its persistence atomic with the death event.

Flat-file startup protects exact persisted marker positions from the already-decoded
Graves.yml records before deferred Folia item preparation runs. This early index
neither exposes claims nor resolves worlds, clones items, or scans blocks. Chunk
admission waits for owner preparation and retained storage snapshots. MySQL still
requires its initial asynchronous record read before exact positions are known.

Startup display repairs remain in the restoration index until the replacement
display/interaction identity is acknowledged by storage. Failed writes reuse the
existing repaired entities and retry through the bounded restoration pass. MySQL
repair updates only metadata for the exact active row, preserving contents and XP.
Login summaries defer while reminder/access/claim stores or grave record loading
are unavailable, and retry on the existing periodic player-owner pass. Disconnect
and listener shutdown discard the pending login request.
