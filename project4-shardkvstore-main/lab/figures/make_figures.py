"""
Generate sequence diagrams for the Project 4 report.
Outputs:
  - single_key_path.png
  - multi_group_2pc.png
"""

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches


# ---------- shared style ----------
LIFELINE_COLOR = "#2b2b2b"
NOTE_FILL = "#fff7d6"
NOTE_EDGE = "#b08900"
ACTIVATION_FILL = "#cde5ff"
ACTIVATION_EDGE = "#1f4e8a"
ARROW_COLOR = "#222"
RETRY_COLOR = "#a04040"
DASHED_COLOR = "#6b6b6b"

LABEL_FONT = dict(fontsize=10, fontweight="bold", ha="center", va="center")
MSG_FONT = dict(fontsize=9, ha="center", va="bottom")
NOTE_FONT = dict(fontsize=8.5, ha="center", va="center")


def _draw_lifeline(ax, x, top, bottom, label):
    """Vertical lifeline + header box."""
    # Header box
    ax.add_patch(mpatches.FancyBboxPatch(
        (x - 1.2, top - 0.3), 2.4, 0.6,
        boxstyle="round,pad=0.05",
        linewidth=1.2, edgecolor=LIFELINE_COLOR, facecolor="#f0f0f0"))
    ax.text(x, top, label, **LABEL_FONT)
    # Lifeline (dashed)
    ax.plot([x, x], [top - 0.3, bottom], linestyle=(0, (3, 3)),
            color=LIFELINE_COLOR, linewidth=1.0, zorder=1)


def _arrow(ax, x_from, x_to, y, label, color=ARROW_COLOR, dashed=False):
    """Horizontal arrow with a centered label above it."""
    style = "->,head_width=0.20,head_length=0.30"
    ls = "--" if dashed else "-"
    ax.annotate(
        "",
        xy=(x_to, y),
        xytext=(x_from, y),
        arrowprops=dict(arrowstyle=style, color=color, linewidth=1.4,
                        linestyle=ls),
    )
    mid_x = (x_from + x_to) / 2
    ax.text(mid_x, y + 0.10, label, color=color, **MSG_FONT)


def _self_arrow(ax, x, y_top, y_bot, label, color=ARROW_COLOR):
    """Self-loop arrow (slight loop to the right)."""
    loop_w = 0.6
    ax.plot([x, x + loop_w, x + loop_w, x], [y_top, y_top, y_bot, y_bot],
            color=color, linewidth=1.2)
    ax.annotate(
        "",
        xy=(x, y_bot),
        xytext=(x + 0.05, y_bot),
        arrowprops=dict(arrowstyle="->,head_width=0.18,head_length=0.25",
                        color=color, linewidth=1.2),
    )
    ax.text(x + loop_w + 0.15, (y_top + y_bot) / 2, label,
            fontsize=8.5, ha="left", va="center", color=color)


def _note(ax, x, y, text, w=3.4, h=0.55):
    """Yellow sticky-note style box for local actions."""
    ax.add_patch(mpatches.FancyBboxPatch(
        (x - w / 2, y - h / 2), w, h,
        boxstyle="round,pad=0.03",
        linewidth=0.9, edgecolor=NOTE_EDGE, facecolor=NOTE_FILL))
    ax.text(x, y, text, **NOTE_FONT)


def _activation(ax, x, y_top, y_bot, w=0.18):
    """Activation bar — disabled (notes already convey local work)."""
    return


# ---------- Figure 1: single-key path ----------
def fig_single_key():
    fig, ax = plt.subplots(figsize=(10, 7.0))
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 14)
    ax.axis("off")

    x_client = 2.5
    x_server = 9.5

    top = 13
    bottom = 0.5

    _draw_lifeline(ax, x_client, top, bottom, "Client")
    _draw_lifeline(ax, x_server, top, bottom, "Owning group\n(group's Paxos)")

    # 1. Client sendCommand (note above arrow)
    _note(ax, x_client, 12.0,
          "sendCommand(Get/Put/Append)\nsequenceNum++, attempt = 0",
          w=4.0, h=0.85)

    # 2. Request to server
    _arrow(ax, x_client, x_server, 11.0,
           "ShardStoreRequest(configNum, key, AMOCommand, attempt)")

    # 3. Server local actions (stack of notes)
    _activation(ax, x_server, 11.0, 8.6)
    _note(ax, x_server, 10.55,
          "configNum match?  group owns shard?\n"
          "shard in currentManagedShards?",
          w=4.5, h=0.80)
    _note(ax, x_server, 9.50,
          "AMO cache hit -> reply cached\n"
          "else propose AMOCommand to Paxos\n"
          "id = sender + \"-\" + currentConfigNum",
          w=4.5, h=1.05)
    _note(ax, x_server, 8.45,
          "Paxos decide -> processKVRequest\n"
          "execute on per-shard AMOApplication",
          w=4.5, h=0.85)

    # 4. Reply
    _arrow(ax, x_server, x_client, 7.4,
           "ShardStoreReply(currentConfigNum, AMOResult)")

    # 5. ClientTimer retry loop
    _self_arrow(ax, x_client, 6.5, 5.5,
                "ClientTimer (100 ms)\nattempt++, resend",
                color=RETRY_COLOR)

    # 6. Note: stale config path
    _note(ax, x_client + 4, 4.5,
          "If reply.configNum > client's view:\n"
          "client refreshes via PingTimer query to ShardMaster.\n"
          "If server.configNum < client's: server replies null,\n"
          "client waits for ClientTimer retry.",
          w=8.5, h=1.5)

    ax.set_title("Single-key path (Get / Put / Append)",
                 fontsize=12, fontweight="bold", pad=12)

    plt.tight_layout()
    out = "figures/single_key_path.png"
    plt.savefig(out, dpi=160, bbox_inches="tight")
    plt.close(fig)
    return out


# ---------- Figure 2: multi-group 2PC ----------
def fig_multi_group_2pc():
    fig, ax = plt.subplots(figsize=(15, 16.0))
    ax.set_xlim(0, 17)
    ax.set_ylim(0, 24)
    ax.axis("off")

    x_client = 1.8
    x_coord = 7.5
    x_part = 14.0

    top = 23
    bottom = 0.5

    _draw_lifeline(ax, x_client, top, bottom, "Client")
    _draw_lifeline(ax, x_coord, top, bottom,
                   "Coordinator group\n(min(participants))")
    _draw_lifeline(ax, x_part, top, bottom,
                   "Participant group(s)")

    # ---- Client sends txn to coord ----
    _arrow(ax, x_client, x_coord, 22.0,
           "ShardStoreRequest(configNum, AMOCommand=Transaction)")

    # ---- Coord: admit & lock ----
    _activation(ax, x_coord, 22.0, 19.6)
    _note(ax, x_coord, 21.5,
          "AMO cache hit?  -> reply cached\n"
          "Already in activeTxns? -> wait\n"
          "pendingConfigChange? -> reject (null reply)",
          w=5.0, h=1.2)
    _note(ax, x_coord, 20.3,
          "propose TxnClientReqCmd to Paxos\n"
          "id = txnClient-<addr>-<cfg>-<attempt>",
          w=5.0, h=0.95)

    # ---- processShardStoreCoordinator on coord ----
    _note(ax, x_coord, 19.1,
          "processShardStoreCoordinator (replicated):\n"
          "tryLockKeys for our keys; participants =\n"
          "groups owning the txn's keys",
          w=5.0, h=1.2)

    # ---- Coord -> Participant: PREPARE ----
    _arrow(ax, x_coord, x_part, 17.6,
           "PrepareTransactionRequest(configNum, AMOCommand, senders, attempt)")

    # ---- Participant processes PREPARE ----
    _activation(ax, x_part, 17.6, 14.5)
    _note(ax, x_part, 17.0,
          "AMO cache hit -> vote YES (cached)\n"
          "weHoldKeys -> vote YES idempotent\n"
          "pendingConfigChange / !canLockKeys -> NO",
          w=5.5, h=1.2)
    _note(ax, x_part, 15.7,
          "propose TxnPrepareReqCmd to Paxos\n"
          "decide -> tryLockKeys; if YES gather\n"
          "readSet values for keys we own",
          w=5.5, h=1.3)

    # ---- Participant -> Coord: PrepareReply ----
    _arrow(ax, x_part, x_coord, 14.2,
           "PrepareTransactionReply(vote, groupId, readValues)")

    # ---- Coord aggregates ----
    _activation(ax, x_coord, 14.2, 12.0)
    _note(ax, x_coord, 13.4,
          "propose TxnPrepareReplyCmd; decide ->\n"
          "aggregate readValues into CoordState\n"
          "all YES -> beginCommitPhase\n"
          "any NO  -> beginAbortPhase",
          w=5.0, h=1.5)

    # ---- Coord beginCommitPhase: run locally + send COMMIT ----
    _note(ax, x_coord, 11.7,
          "beginCommitPhase: build fullReadValues =\n"
          "aggregated + own readSet values;\n"
          "executeTransactionOnOwnedShards(fullReadValues)\n"
          "phase = COMMITTING; ack self",
          w=5.0, h=1.5)

    _arrow(ax, x_coord, x_part, 10.2,
           "CommitTransactionRequest(commit=true, fullReadValues, attempt)")

    # ---- Participant processes COMMIT ----
    _activation(ax, x_part, 10.2, 7.6)
    _note(ax, x_part, 9.5,
          "Cache hit -> reply cached partial\n"
          "configNum mismatch + holds lock -> paxos\n"
          "(cache check BEFORE configNum check)",
          w=5.5, h=1.3)
    _note(ax, x_part, 8.1,
          "propose TxnCommitReqCmd; decide ->\n"
          "txn.run(db) using shipped readValues;\n"
          "write own keys; releaseLocks; cache partial",
          w=5.5, h=1.5)

    # ---- Participant -> Coord: CommitReply ----
    _arrow(ax, x_part, x_coord, 7.0,
           "CommitTransactionReply(committed, groupId, partialResult)")

    # ---- Coord finishCommit ----
    _activation(ax, x_coord, 7.0, 4.0)
    _note(ax, x_coord, 6.4,
          "propose TxnCommitReplyCmd; decide ->\n"
          "st.acks.add(groupId)\n"
          "all acks received -> finishCommit",
          w=5.0, h=1.3)
    _note(ax, x_coord, 5.0,
          "finishCommit: mergePartials,\n"
          "txnAmoCache.put, activeTxns.remove,\n"
          "releaseLocks; maybeTrigger pending config",
          w=5.0, h=1.5)

    # ---- Coord -> Client final reply ----
    _arrow(ax, x_coord, x_client, 3.5,
           "ShardStoreReply(currentConfigNum, AMOResult)")

    # ---- Notes at bottom ----
    _note(ax, x_coord, 2.4,
          "Retries: PrepareTimer / CommitTimer\n"
          "bump 'attempt' so each retry message creates\n"
          "a fresh paxos slot at the receiver (avoids dedup-drop).",
          w=6.0, h=1.5)

    _note(ax, x_part, 2.4,
          "Abort path: beginAbortPhase broadcasts\n"
          "CommitTransactionRequest(commit=false) to ALL\n"
          "participants so a slow PREPARE that lands\n"
          "after we abort still gets its lock released.\n"
          "Coord sends null reply to client immediately.",
          w=6.0, h=1.7)

    ax.set_title("Multi-group transaction (Two-Phase Commit)",
                 fontsize=12, fontweight="bold", pad=12)

    plt.tight_layout()
    out = "figures/multi_group_2pc.png"
    plt.savefig(out, dpi=160, bbox_inches="tight")
    plt.close(fig)
    return out


if __name__ == "__main__":
    print(fig_single_key())
    print(fig_multi_group_2pc())
