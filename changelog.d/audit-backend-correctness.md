### Fixed
- Role administration rejects stale permission and description edits independently; announcement reads no longer create rows and concurrent first writes retain a single current banner.
- Exchange change sets remove every undeclared field even after the warning limit, and concurrent DPoP proof claims respect the global capacity.
- Nested transactions keep their live updates and cached reads separate; leadership and refinery overview counters use batched reads.
- Bank PDF exports retain complete histories while rendering posting batches and streaming the finished download from private temporary storage.
