# Organize the codebase around five deep modules

KGS Notes will begin with `:app`, `:notes-engine`, `:source:nextcloud`, `:editor`, and `:design-system`. Persistence, indexing, queued synchronization, and conflict handling live behind the small interface of the deep `:notes-engine` module instead of being exposed as shallow data and repository layers; Nextcloud and an in-memory test implementation act as source adapters at the seam owned by that engine. Hilt performs constructor injection and Android lifecycle wiring, but its types and annotations stay outside the engine's public interface.
