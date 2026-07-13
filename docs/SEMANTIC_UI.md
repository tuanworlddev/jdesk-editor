# Semantic UI

Stable IDs, snapshots, actions, and revisions (spec §9).

## Semantic IDs

Every actionable UI element carries a stable, workspace-relative semantic id — independent of React
indexes or CSS classes, retained across rerender/resize/sort. Grammar in use:

```
folder:<relPath>     file:<relPath>     tab:<relPath>     editor:<relPath>
```

Explorer rows and tabs render `data-semantic-id`; agents and the E2E harness resolve targets by id,
never by coordinates. Proven: E2E-01 (Explorer ids), `…105336Z-e06d` S1 (id stability across
rerender).

## Pointer

The pointer overlay computes target geometry **in the page** at execution time and asserts its
center lands inside the target's bounding rect — coordinates never cross the bridge (proven
`…105336Z-e06d` S2). A full choreography queue (ACCEPTED→COMMITTED→PRESENTED) with the three
presentation modes is the remaining Phase-2 work; the presentation modes themselves are implemented
and byte-identical (`…121445Z-a099`).

## Edit transactions

Agent edits are one undo group (pushStackElement before/after, no stops between) and reach
byte-identical final content in all three presentation modes. Java verifies the content hash against
the frontend hash (`@noble/hashes` == `MessageDigest`, proven S3-JAVA).
