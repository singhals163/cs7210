# Project 4: Report

## Intro (Your understanding)

This lab implements a sharded, transactional key-value store on top of the
DSLabs framework, reusing the Paxos engine from Project 3 for replication
inside each replica group. The system has three kinds of nodes:

1. **`ShardMaster`:** A Paxos-replicated configuration service. It owns a
   numbered sequence of `ShardConfig`s, each mapping every shard (1..N) to
   exactly one replica group, and exposes `Join` / `Leave` / `Move` /
   `Query`.
2. **`ShardStoreServer`:** One process per replica per group. Each group
   runs its own Paxos sub-node and serves the keys for the shards it
   currently owns under the latest `ShardConfig` it has applied.
3. **`ShardStoreClient`:** Issues `Get` / `Put` / `Append` (single-key) and
   `Transaction` (`MultiGet`, `MultiPut`, `Swap` — possibly multi-shard,
   multi-group) commands.

The work of the lab is to: (a) implement the `ShardMaster` and its
rebalancing algebra, (b) coordinate shard handoffs between groups when the
config advances, (c) replicate every state-mutating event in a group
through that group's Paxos so all replicas converge, and (d) run cross-
shard transactions with two-phase commit while staying atomic, isolated,
and idempotent under message loss and reorder.

## Flow of Control & Code Design

### 1. ShardMaster (`ShardMaster.java`)

* Maintains `currentConfigNum` and `configs: Map<Integer, ShardConfig>`.
* `Join(g, servers)`: deep-copies the current config, adds the new group
  with empty shards, calls `balanceConfig()` which moves shards one at a
  time from the largest group to the smallest until `max - min ≤ 1`.
* `Leave(g)`: deep-copies, removes group `g`, redistributes its shards to
  the remaining groups via a min-heap (smallest group first) so the result
  stays balanced.
* `Move(g, shardNum)`: deep-copies, transfers `shardNum` to group `g`.
* `Query(n)`: read-only — returns config `n` (or the latest if `n == -1`).

### 2. Single-key path

```
Client                          Owning group
  |                                 |
  | sendCommand(Get/Put/Append)     |
  | --(ShardStoreRequest)-->        |
  |                                 | configNum match?  shard owned and present
  |                                 |   in currentManagedShards?
  |                                 | propose AMOCommand to group's Paxos
  |                                 | -- decide --> per-shard AMOApplication
  | <-(ShardStoreReply)----         |
  | ClientTimer drives retries      |
```

Each shard has its own `AMOApplication<TransactionalKVStore>` so retries
are deduped per-shard.

### 3. Multi-group transaction path (2PC)

```
Client                Coord group              Participant group(s)
  |                       |                          |
  |--(ShardStoreRequest)->|                          |
  |                       | propose TxnClientReqCmd  |
  |                       | -- decide -->            |
  |                       | tryLockKeys for owned    |
  |                       |   keys; participants =   |
  |                       |   groups touched         |
  |                       | I'm min(participants)?   |
  |                       |--(PrepareTransactionRequest)->|
  |                       |                          | propose TxnPrepareReqCmd
  |                       |                          | -- decide -->
  |                       |                          | tryLockKeys; gather
  |                       |                          |   readSet values for
  |                       |                          |   keys we own
  |                       | <-(PrepareReply: vote, readValues)-|
  |                       | propose TxnPrepareReplyCmd                    |
  |                       | -- decide --> on every YES, beginCommitPhase  |
  |                       |               aggregate readValues            |
  |                       |                                               |
  |                       |--(CommitTransactionRequest, fullReadValues)-->|
  |                       |                          | propose TxnCommitReqCmd
  |                       |                          | -- decide -->
  |                       |                          | run txn locally with
  |                       |                          |   the full pre-image
  |                       |                          |   db; release locks;
  |                       |                          |   cache partial
  |                       | <-(CommitReply: partial)-|
  |                       | propose TxnCommitReplyCmd                     |
  |                       | -- decide --> add to acks                     |
  |                       | acks complete -> finishCommit:                |
  |                       |   merge partials, cache, releaseLocks         |
  | <-(ShardStoreReply)---|                                                |
```

Single-group transactions skip 2PC: the coord runs `txn.run` over its own
shards directly, caches the result, and replies. `beginAbortPhase` mirrors
the commit path with `commit=false` and broadcasts to *every* participant
(not only the YES voters) so a slow PREPARE that lands at a participant
after the abort decision still gets its lock released; the null reply to
the client is sent immediately at the start of abort so the client can
retry without waiting for acks.

### 4. Replicated wrapper commands

Every event that mutates server state goes through the group's Paxos via a
wrapper `Command`: `ShardStoreCommand`, `NewConfigCmd`, `ShardMoveCmd`,
`ShardMoveAckCmd`, `TxnClientReqCmd`, `TxnPrepareReqCmd`,
`TxnPrepareReplyCmd`, `TxnCommitReqCmd`, `TxnCommitReplyCmd`. The
convention throughout is:

* `handle*(...)` runs on receipt — does pre-paxos checks, AMO/cache
  fast paths, then proposes the wrapper to Paxos.
* `process*(...)` runs from `handlePaxosDecision` and is the replicated
  body: every replica sees the same command in the same order.

### 5. Reconfiguration & shard handoff

* `PingTimer` (every 100 ms) calls `sendConfigRequest(currentConfigNum + 1)`
  to the ShardMaster (only when `isStable()` to avoid query storms during
  transfers).
* On `handlePaxosReply` carrying `ShardConfig N+1`:
    * if `isStable() && canApplyConfigChange()` → propose `NewConfigCmd(N+1)`
      with a fresh `paxosProposalAttempt` suffix.
    * else stash as `pendingConfig` (and set `pendingConfigChange` so we
      reject new client work until it drains).
* `processNewConfig(N+1)` updates `currentConfigNum` / `currentConfig`,
  clears the pending flag, and calls `handleMove()` which sends a
  `MoveRequest` for every shard the group used to own but no longer does.
* The receiver's `processMoveRequest` admits the shard into
  `app[shardId]` and acks via `MoveReply`. The sender's `processMoveReply`
  removes the shard. `currentManagedShards == currentConfig[groupId].shards`
  is the `isStable()` predicate.
* `maybeTriggerPendingConfigChange()` is called whenever a lock is
  released or an active txn finishes; it arms a 1 ms `ConfigProposeTimer`
  that, when it fires, proposes the stashed `NewConfigCmd`. We *cannot*
  propose synchronously from the lock-release paths because they run inside
  a Paxos decide handler — see Design Decisions.

### 6. Cross-shard transaction execution

Each participant evaluates the txn in `executeTransactionOnOwnedShards`,
which builds a `db: Map<String, String>` and calls `txn.run(db)`. The
catch is that `Swap.run(db)` reads BOTH keys and writes BOTH. If a
participant only has its own keys in `db`, it sees the other key as
"missing" and falls into the *delete* branch — both sides delete
their own key. We solve this by **shipping read values across the 2PC**:

* On a YES vote, each participant returns the values of its readSet keys
  in `PrepareTransactionReply.readValues`.
* The coord aggregates them in `CoordState.readValues`, adds its own local
  values (it never sent itself a PREPARE), and ships the combined
  `fullReadValues` map in `CommitTransactionRequest`.
* Every participant feeds that map into `executeTransactionOnOwnedShards`
  so `txn.run` sees a *full* pre-image db. Each side still writes back
  only the keys it owns; coord merges partials in `mergePartials`.

## Design Decisions

* **Per-key locks over a server-wide lock.** An earlier implementation
  serialized all transactions in a group on one lock, collapsing throughput
  in multi-client stress tests. `Map<String, AMOCommand> keyLocks` lets
  disjoint transactions run concurrently and is re-entrant: a re-prepare
  for the same `AMOCommand` is treated as already-held.
* **Per-transaction `CoordState` map.** `Map<AMOCommand, CoordState>
  activeTxns` lets a coord drive multiple transactions concurrently —
  phase, prepareYes set, acks set, partials, and aggregated readValues
  all live in `CoordState`.
* **`attempt` counter on retried client / PREPARE / COMMIT messages.** The
  Project 3 `PaxosServer` keys executed-record by `(id, seqNum)`. Without
  a per-retry suffix in the id, a request that was rejected post-paxos
  (transient lock conflict, configNum mismatch, etc.) gets its retry
  silently dedup-dropped on the next attempt — the coord then waits
  forever. The client bumps `attempt` on `onClientTimer`; coord bumps it
  on `PrepareTimer` / `CommitTimer`.
* **`paxosProposalAttempt` on internal proposals.** `newConfig-N`,
  `shardMove-S-N`, and `shardMoveAck-S-N` were originally keyed only on
  configNum/shardId. When a `process*` handler early-returns post-paxos
  (e.g., `processNewConfig` finds `newConfig.configNum() != currentConfigNum
  + 1` after an intervening apply), the next `PingTimer` re-proposes with
  the same id and Paxos's dedup swallows it forever. Bumping a per-propose
  counter on the id guarantees every retry gets a fresh slot.
* **Cache-before-configNum on retried COMMITs.** A retry of an already-
  committed COMMIT must return the cached partial regardless of any
  subsequent config drift — replying `partial=null` after we've cached the
  real partial would let the coord's `mergePartials` produce phantom
  `KEY_NOT_FOUND`s for keys we actually wrote. The cache check therefore
  precedes the configNum check in both `handle` and `process` versions of
  `CommitTransactionRequest`.
* **Deferred reconfiguration.** We never apply `ShardConfig N+1` while any
  lock is held (`canApplyConfigChange()` checks both `keyLocks.isEmpty()`
  and `activeTxns.isEmpty()`). This preserves the invariant that a server
  processing a transaction at config N stays at config N for the entire
  2PC, so coord and participants always agree on shard ownership.
* **`min(participants)` coordinator selection.** Both client and server
  compute the coordinator deterministically as the smallest groupId among
  the groups owning at least one of the txn's keys at the current config.
  Servers that aren't the coord under their own view drop the request and
  the client retries.
* **Shipping readValues across 2PC.** Keeps `txn.run(db)` untouched while
  letting cross-shard writes (Swap) compute correctly. Each side still
  writes only the keys it owns; the rest of the new db state is
  discarded.
* **`ConfigProposeTimer` (1 ms) breaks paxos drain re-entry.**
  `maybeTriggerPendingConfigChange` is called from inside Paxos decide
  handlers (e.g., `processCommitTransactionRequest`). Project 3's
  `PaxosServer.updatePaxosLog` calls `sendRequestReply` *before*
  incrementing `slotOut`, so a synchronous re-entrant
  `handleMessage(PaxosRequest, paxosAddress)` re-enters the same drain at
  the same `slotOut` and recurses without bound (`StackOverflowError`
  reproduced in test 4.7). Arming a 1 ms timer pushes the propose to the
  next event-loop iteration. A `configProposeTimerArmed` flag prevents
  queueing duplicate timers when several lock-release paths fire in quick
  succession.
* **Defensive `releaseLocks` on configNum-mismatched COMMIT/ABORT.** By
  invariant, a participant holding a lock for txn X cannot have advanced
  past X's config. If that invariant is ever violated, the configNum-
  mismatch path used to ack without releasing the lock, producing a
  permanent wedge. We now release locks for the command on the mismatch
  path — pre-paxos when no lock is held (direct ack), post-paxos when a
  lock is held (replicated via Paxos).

## Missing Components

* **Test 4.7 (`test07ConstantMovementSingleServer`)** — constant shard
  movement (every 4 s) + `networkDeliverRate(0.8)`. Catch-up to new
  configs is bottlenecked on `onPingTimer` only querying the ShardMaster
  while `isStable()` and `handlePaxosReply` only acting on a config when
  `isStable() && canApplyConfigChange()`. Under sustained drops the group
  can fall multiple configs behind and the per-client `MaxWaitTime`
  exceeds the 4000 ms allowance. We tried always-querying the ShardMaster,
  stashing configs mid-move, and an `isReconfiguring()` rejection driven
  by a `maxConfigNum` watermark; each fixed 4.7 partially but regressed
  the reliable / non-movement tests. The submitted code is the
  conservative point that passes the rest of the suite.
* **Search tests (4.10 `test10SingleServerRandomSearch`, 4.18, etc.)** —
  state space explodes with the number of internal Paxos slots; each
  retry with `attempt+1` is a distinct state, and search at
  `maxDepth(1000)` runs out of time before reaching the goal predicate.

## References

* **[Lab 4 Handout](https://github.gatech.edu/cs7210-spr26/project4-shardkvstore):** Spec for `ShardMaster`, `ShardStoreServer`, `ShardStoreClient`, the message contract, and the test invariants (RESULTS_OK, MULTI_GETS_MATCH, max-wait-time bound).
* **[Lab 3 Handout](https://github.gatech.edu/cs7210-spr26/project3-paxos):** Paxos engine reused unchanged here as the per-group replication backbone; reviewed its `whetherRequestComplete` / `whetherRequestInProposal` dedup contract while debugging the `(id, seqNum)` issues that led to the `attempt` counters.
* **DSLabs framework source (`framework/`):** `Node.handleMessage`, sub-node addressing via `Address.subAddress`, and `set(Timer, delay)` semantics — needed to choose between synchronous reentry and timer-deferred propose for `maybeTriggerPendingConfigChange`.
* **Course lecture notes** on Multi-Paxos (log-structured replication) and Two-Phase Commit (PREPARE/COMMIT message exchange).
* **Tanenbaum, *Distributed Systems*** — 2PC protocol overview used for the abort/commit phase design.
* **Anthropic Claude (Sonnet/Opus)** as a coding assistant. Used for: rubber-ducking the cross-shard Swap correctness bug under partial reads, locating the post-paxos dedup issue with stable `newConfig-N` ids, and identifying the recursive `maybeTriggerPendingConfigChange` ↔ `updatePaxosLog` interaction that produced a `StackOverflowError` in test 4.7. Prompts pasted failing test logs and asked for a reading of the message-flow trace, then sanity-checked proposed fixes against the existing code; final implementations, invariants, and the design choices listed above are ours.
* **[Java `HashMap` / `Map` interface](https://docs.oracle.com/javase/8/docs/api/java/util/Map.html)** — for `entrySet().removeIf(...)` (used in `releaseLocks`) and re-entrant `put` semantics on the `keyLocks` map.
* **[Apache Commons Lang `Pair`](https://commons.apache.org/proper/commons-lang/apidocs/org/apache/commons/lang3/tuple/Pair.html)** — used in `ShardConfig.groupInfo()` to bundle each group's servers and shards.

## Extra (Optional)

**Problem.** `dslabs.paxos.PaxosServer.updatePaxosLog` calls
`sendRequestReply(...)` *before* incrementing `slotOut`:

```java
while (slotOut < slotIn) {
    if (PaxosLog.get(slotOut).status != CHOSEN) break;
    PaxosRequest request = PaxosLog.get(slotOut).paxosRequest();
    sendRequestReply(request);          // <-- side effect; can re-enter
    updateExcutedRecord(slotOut, request);
    slotOut += 1;
}
```

If `sendRequestReply` triggers any code path that ends up calling
`handleMessage(new PaxosRequest(...), paxosAddress)` synchronously (for
example, the application's commit handler doing a follow-up paxos
propose), the inner drain re-reads the same `slotOut`, finds it still
CHOSEN, and recursively drains the same slot — `StackOverflowError`. We
hit this in test 4.7 from `processCommitTransactionRequest →
maybeTriggerPendingConfigChange → handleMessage(PaxosRequest("newConfig-..."), paxosAddress)`.

**Proposed fix.** Increment `slotOut` *before* calling `sendRequestReply`,
so a re-entrant drain finds the now-incremented `slotOut` and either
breaks (next slot not CHOSEN) or processes the *next* slot once:

```java
while (slotOut < slotIn) {
    PaxosLogSlot slot = PaxosLog.get(slotOut);
    if (slot.status != CHOSEN) break;
    PaxosRequest request = slot.paxosRequest();
    int processed = slotOut;
    slotOut += 1;                       // advance first
    updateExcutedRecord(processed, request);
    sendRequestReply(request);          // safe to re-enter; we won't see this slot again
}
```

This makes the drain re-entrant-safe and removes the need for application
code to defer follow-up Paxos proposals via timers (our current
`ConfigProposeTimer` workaround).
