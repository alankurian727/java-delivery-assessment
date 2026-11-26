# 003 · Recover from pricing failures

**Status** · Planned

Call Pricing with bounded timeouts and no automatic retries in the initial implementation. A transient failure returns HTTP 503 while preserving the key binding. A caller can retry with the same key and input once Pricing recovers. Existing order reads use stored snapshots.
