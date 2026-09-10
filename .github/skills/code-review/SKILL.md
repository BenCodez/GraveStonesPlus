---
name: code-review
description: >-
  Review GraveStonesPlus pull requests, branch diffs, commits, and explicitly
  included local changes before publishing. Use for code review, pre-PR review,
  regression review, security review, and PR readiness. Perform an independent
  source-read-only review and report only concrete P0-P3 defects with precise
  file/line locations. Do not use this skill to implement fixes.
---

# GraveStonesPlus code review

Review the exact proposed change, following applicable `AGENTS.md`. Prioritize item/experience preservation, exactly-once claims, durable recovery, platform safety, compatibility, and security.

## Boundaries and scope

Do not edit, fix, commit, push, approve, merge, change PR state, or post comments independently. Preserve unrelated work; never stash, reset, clean, rebase, or switch branches. Run only safe bounded checks under existing permissions and never access a live server or production database.

Resolve the actual PR base and SHA, merge base, review HEAD SHA, commit list, complete patch, changed paths, and worktree state. Review every change in the merge-base-to-HEAD range. Disclose local overlays, untracked/generated/binary material, missing history, multiple merge bases, conflicts, truncation, or unavailable files. Pin and recheck the reviewed snapshot.

Use a fresh reviewer context for substantive work when supported. A focused follow-up to old findings does not replace the final fresh review.

## GraveStonesPlus review lenses

Trace the affected state machine end to end: death/drop capture, record creation, persistence, marker creation, visibility/protection, interaction, GUI transfer, claim, storage mutation, marker removal, expiration, reload, disable, and restart.

- Look for item or XP loss/duplication when creation, serialization, SQL/YAML writes, marker spawning, inventory insertion, event callbacks, or cleanup partially fail.
- Test concurrent/double claims, repeated events, retries, disconnects, full/partially full inventories, cancellation, plugin disable, and crash/restart boundaries.
- Verify the durable ordering of claim/removal. A record must not remain claimable after rewards are delivered, and contents must remain recoverable when delivery did not finish.
- Check consistency among live grave objects, markers, scheduled tasks, YAML/MySQL records, caches, and public events.
- Check world UUID/location identity, missing worlds, chunk load/unload, explosions, pistons/fluids, marker replacement, expiry, and stale entities where touched.
- Recheck ownership and permissions immediately before mutation. Cancellable claim/interact/remove events must have coherent timing and no partial side effects when cancelled.
- Verify Bukkit/Folia thread ownership; flag blocking SQL/filesystem calls on server or region threads and unsafe async world, entity, inventory, or player access.
- Preserve serialized `ItemStack` data, enchantments, metadata, quantities, experience, legacy YAML records, SQL schema, and migration behavior.
- Inspect bounds on grave counts, item payloads, queries, retries, cleanup scans, and diagnostics; check cancellation, executor rejection, connection failure, and shutdown cleanup.
- Verify AdvancedCore/shading/dependency changes against actual pinned contracts and packaged artifact contents.
- Missing tests alone are not a finding; demonstrate a broken behavior or defective test contract.

## Validation

Confirm current CI/POM requirements. At the time this skill was added:

```shell
mvn -B -f GraveStonesPlus/pom.xml package
```

Record the working directory, command, snapshot, exit result, discovered tests, and fresh artifact. Run `git diff --check`. Do not count `-DskipTests`, zero discovered tests, stale artifacts, or a different/dirty snapshot as proof. Distinguish introduced failures, reproduced baseline failures, and environmental blockers.

## Findings and result

Verify each candidate against reachable code, responsible changed lines, existing guards, expected behavior, and realistic impact. Drop speculation, nits, unrelated old defects, and findings contradicted by final code. Use the lowest accurate priority:

- P0: immediate critical release blocker.
- P1: high-impact loss/duplication, corruption, outage, deadlock, major compatibility break, or serious security exposure.
- P2: concrete bounded or edge-case correctness, reliability, resource, or security defect.
- P3: low-impact concrete defect.

Anchor findings to the smallest useful changed-line range and explain trigger, mechanism, and consequence.

Determine completeness separately from findings: report verified findings for a complete review; use exactly `No findings.` when complete and clean; use `Review incomplete.` only when required coverage or validation is missing, unresolved, or stale. A static-only review may be complete in scope but does not satisfy a build gate.

The implementation coordinator fixes accepted findings, reruns validation, and obtains a fresh review. This reviewer never publishes or merges.
