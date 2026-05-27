package orca.backend

import orca.llm.{BackendTag, SessionId}

/** Whether a backend call against a given caller-supplied session id should
  * start a fresh session or resume an existing one. Each backend has its own
  * scheme for tracking which is which (claude claims a caller-allocated UUID
  * via `--session-id`; codex maps client UUIDs to server-allocated thread
  * ids), but the call site only cares about the two cases.
  *
  * The carried id is the id to put on the wire — claude uses the same
  * client-supplied id for both arms; codex resumes against the server-side
  * thread id learned from `thread.started`. Backends that don't use the id
  * (e.g. codex's fresh `exec`, which mints its own) simply ignore it.
  */
enum Dispatch[B <: BackendTag]:
  case Fresh(id: SessionId[B])
  case Resume(id: SessionId[B])

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
  * Implementations must be thread-safe — flows run reviewers in parallel
  * via `mapParUnordered`, each holding its own session id; the backend is
  * a per-flow singleton.
  */
trait SessionRegistry[B <: BackendTag]:
  def dispatchFor(client: SessionId[B]): Dispatch[B]
  def commitSuccess(client: SessionId[B], server: SessionId[B]): Unit

object SessionRegistry:

  /** For backends whose client-supplied session id IS the canonical id on
    * the wire. The first use of an id is a fresh session; subsequent uses
    * resume it. `commitSuccess` just records that the id has been claimed.
    * `server` is ignored — it equals `client` for these backends.
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

    def commitSuccess(client: SessionId[B], server: SessionId[B]): Unit =
      val _ = claimed.add(SessionId.value(client))

  /** For backends whose session id is server-minted at first use, learned
    * from the protocol response. The framework hands the caller a stable
    * client id; the backend maps it to whatever the wire id turns out to
    * be and resumes against that.
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
