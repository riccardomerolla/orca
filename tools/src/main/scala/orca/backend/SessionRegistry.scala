package orca.backend

import orca.llm.{BackendTag, SessionId}

/** Whether a backend call against a given caller-supplied session id should
  * start a fresh session or resume an existing one. Each backend has its own
  * scheme for tracking which is which (claude claims a caller-allocated UUID
  * via `--session-id`; codex maps client UUIDs to server-allocated thread
  * ids), but the call site only cares about the two cases.
  *
  * `wireId` is the id the consumer puts on the wire — the registry has
  * already decided which one it is (claude's client UUID, codex's server
  * thread id). Consumers shouldn't reason about where it came from; they
  * pattern-match on `Fresh` vs `Resume` and forward the id to the CLI.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(wireId: SessionId[B])
  case Resume(wireId: SessionId[B])

/** Backend-internal bookkeeping for the fresh-vs-resume decision.
  *
  * Two roles:
  *
  *   - [[dispatchFor]] reads the registry's state and decides what the next
  *     call against `client` should look like on the wire.
  *   - [[commitSuccess]] records a successful invocation so subsequent calls
  *     pick the right [[Dispatch]]. Backends with caller-allocated ids
  *     (claude) ignore `server` and just remember that `client` has been
  *     used. Backends with server-minted ids (codex) record the
  *     client→server mapping.
  *
  * When `commitSuccess` fires is a per-backend policy decision: claude
  * commits as soon as the spawn succeeds (the client id is already on the
  * wire); codex commits post-drain (autonomous) or post-`interaction.drive`
  * (interactive) because the server id isn't known until the protocol
  * surfaces `thread.started`. The SPI doesn't constrain timing.
  *
  * Thread safety: implementations must be safe under concurrent reads and
  * writes — flows run reviewers in parallel via `mapParUnordered`, and the
  * backend is a per-flow singleton. The `dispatchFor` → spawn →
  * `commitSuccess` *sequence* is NOT atomic, however; two concurrent calls
  * with the SAME client id would both see `Fresh` and both spawn. Callers
  * must therefore not share a session id across concurrent calls — the
  * reviewer fan-out is safe because each reviewer mints its own distinct
  * id via `LlmTool.newSession`.
  */
trait SessionRegistry[B <: BackendTag]:
  def dispatchFor(client: SessionId[B]): Dispatch[B]
  def commitSuccess(client: SessionId[B], server: SessionId[B]): Unit

object SessionRegistry:

  /** For backends whose client-supplied session id IS the canonical id on
    * the wire. The first use of an id is a fresh session; subsequent uses
    * resume it. `commitSuccess` just records that the id has been claimed.
    *
    * Claude's `--session-id <uuid>` (claim) → `--resume <uuid>` (continue)
    * mapping uses this.
    */
  final class ClaimedOnce[B <: BackendTag] extends SessionRegistry[B]:
    private val claimed =
      java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

    def dispatchFor(client: SessionId[B]): Dispatch[B] =
      if claimed.contains(SessionId.value(client)) then Dispatch.Resume(client)
      else Dispatch.Fresh(client)

    /** The `server` parameter is ignored — for backends using this
      * registry, the wire id IS the client id.
      */
    def commitSuccess(client: SessionId[B], server: SessionId[B]): Unit =
      val _ = claimed.add(SessionId.value(client))

  /** For backends whose session id is server-minted at first use, learned
    * from the protocol response. The framework hands the caller a stable
    * client id; the backend maps it to whatever the wire id turns out to
    * be and resumes against that.
    *
    * `commitSuccess` uses `putIfAbsent` — the first successful call wins.
    * A subsequent commit with a different server id for the same client
    * is silently dropped; this matches the protocol invariant that
    * resuming a session never changes its server-side id.
    *
    * Codex's `codex exec` (mints) → `codex exec resume <server-id>`
    * (continue) mapping uses this.
    */
  final class ClientToServer[B <: BackendTag] extends SessionRegistry[B]:
    private val map =
      new java.util.concurrent.ConcurrentHashMap[String, String]()

    def dispatchFor(client: SessionId[B]): Dispatch[B] =
      Option(map.get(SessionId.value(client))) match
        case Some(serverId) => Dispatch.Resume(SessionId[B](serverId))
        case None           => Dispatch.Fresh(client)

    def commitSuccess(client: SessionId[B], server: SessionId[B]): Unit =
      val _ = map.putIfAbsent(
        SessionId.value(client),
        SessionId.value(server)
      )
