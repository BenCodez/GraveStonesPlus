# Maintainer and AI-agent guide

GraveStonesPlus is a Bukkit/Paper/Folia plugin built on AdvancedCore. Its primary safety requirement is preserving every captured item and experience value exactly once through death, persistence, marker creation, interaction, claim, removal, expiration, reload, and restart.

## Build and verification

Requirements: JDK 21+ and Maven. The Maven project is in `GraveStonesPlus/`.

```shell
mvn -B -f GraveStonesPlus/pom.xml test
mvn -B -f GraveStonesPlus/pom.xml package
```

Confirm current CI and POM settings. Verify the fresh shaded JAR, actual test discovery, and `git diff --check`. Do not run deployment or developer-copy profiles for routine validation.

## Architecture and data ownership

- `GraveStonesPlus` owns plugin lifecycle, listener registration, commands, and storage initialization.
- `graves/Grave` represents live grave behavior and marker state.
- `storage/GravesConfig` is the serialized grave record.
- `storage/GraveStorageManager` selects and coordinates the configured backend.
- `storage/GraveLocations` owns YAML persistence.
- `storage/GravesMySQLTable` owns SQL persistence and identity.
- `events/` exposes cancellable interaction, claim, removal, and related API events.
- Configuration and resource files define material, ownership, protection, expiration, inventory, and persistence behavior.

A grave's identity and state transition must remain consistent across memory, world marker, YAML/MySQL storage, scheduled tasks, and exposed events.

## Grave lifecycle invariants

1. Capture eligible death drops and experience before destructive mutation, and record ownership/location/world identity without losing or duplicating values.
2. Do not remove items from the death event unless the grave state is safely established according to the configured failure policy.
3. Claim is exactly-once: concurrent clicks, double-clicks, retries, inventory overflow, event callbacks, disconnects, and storage failure must not duplicate or discard items/experience.
4. Persist the claim/removal transition in an order that is atomic or recoverable. Never advertise success while durable state still allows the same grave to be claimed again.
5. Preserve unclaimed contents when a player's inventory cannot accept everything. Make overflow/drop behavior explicit and test it.
6. Reload, disable, restart, world unload, chunk unload/load, marker destruction, explosion, piston/fluid behavior, expiration, and missing worlds must not orphan live graves or silently delete stored graves.
7. Respect ownership, permissions, protection windows, and cancellable API events at the final mutation boundary, not only when opening a GUI.
8. Keep Bukkit/world/entity/inventory access on the correct Bukkit or Folia execution context; keep SQL and filesystem work off server/region threads.
9. Use stable UUID/world/location identity and preserve legacy serialized records and database schemas through explicit migrations.
10. Optional integrations and marker implementations must remain guarded when dependencies, worlds, entities, or materials are unavailable.

## Persistence and concurrency

Coordinate cache and backend updates. Snapshot mutable collections crossing threads; bound queues, retries, query results, serialized item sizes, and scheduled recovery work. Handle null/closed connections, executor rejection, cancellation, interruption, partial startup, rollback failure, and shutdown flushes explicitly.

Do not log database credentials, serialized inventories, player data, or unrestricted configuration. Validate configuration-derived paths, identifiers, materials, durations, and counts before use.

## Change and PR workflow

Keep changes focused. Before any commit, push, PR update, review reply, or other remote change, run focused checks, the full Maven package build, fresh-artifact inspection, and `git diff --check`; then inspect the complete base-to-HEAD diff. Before committing local work, also inspect the staged changes and every relevant intended unstaged or untracked change as one effective final patch.

For substantive work, obtain a fresh source-read-only review. The implementation agent verifies and fixes accepted findings, reruns validation, and obtains a new review of the updated snapshot. Do not reuse an old clean verdict after changes, and do not merge without explicit authorization.
