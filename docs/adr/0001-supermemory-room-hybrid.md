# Room-only Memory

Status: amended 2026-11-23 — Supermemory dropped from the shipped path (supersedes the hybrid above).

Supermemory has no direct create or update route (async extraction), so deletes cannot mirror reliably; recall quality is a graded design area we must defend with our own retrieval, not a black-box cloud call; and it routes mail content to a third party. The shipped path is Room-only behind the `MemoryStore` interface (`RoomMemoryStore` is the only wiring in `AgentViewModel`); the dead `Outbox` is deleted, not wired; `SUPERMEMORY_API_KEY` is removed from `local.properties.sample`. The P1 memory upgrade (category, importance, embedding cosine plus recency boost, FTS fallback when offline, `save_memory` as an LLM tool) builds on Room. Keyword plus recency ships first; embeddings are cuttable.
