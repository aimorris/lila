package lila.memo

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._

/**
 * A synchronous cache from asynchronous computations.
 * It will attempt to serve cached responses synchronously.
 * If none is available, it starts an async computation,
 * and either waits for the result or serves a default value.
 */
final class Syncache[K, V](
    name: String,
    compute: K => Fu[V],
    default: K => V,
    strategy: Syncache.Strategy,
    timeToLive: FiniteDuration,
    logger: lila.log.Logger,
    resultTimeout: FiniteDuration = 5 seconds)(implicit system: akka.actor.ActorSystem) {

  import Syncache._

  // ongoing async computations
  private val chm = new ConcurrentHashMap[K, Fu[V]]

  // sync cached values
  private val cache = Builder.expiry[K, V](timeToLive)

  // get the value synchronously, might block depending on strategy
  def sync(k: K): V = Option(cache getIfPresent k) getOrElse {
    // println(s"*** $name miss $k")
    // Thread.dumpStack()
    incMiss()
    chm.computeIfAbsent(k, loadFunction)
    strategy match {
      case NeverWait            => default(k)
      case AlwaysWait(duration) => waitForResult(k, duration)
      case WaitAfterUptime(duration, uptime) =>
        if (lila.common.PlayApp startedSinceSeconds uptime) waitForResult(k, duration)
        else default(k)
    }
  }

  // get the value asynchronously, never blocks (preferred)
  def async(k: K): Fu[V] = Option(cache getIfPresent k) match {
    case Some(v) => fuccess(v)
    case None =>
      chm.computeIfAbsent(k, loadFunction)
      chm get k
  }

  def invalidate(k: K): Unit = cache invalidate k

  def preloadOne(k: K): Funit =
    if (cache.getIfPresent(k) == null) {
      incPreload()
      // println(s"*** $name preload $k")
      chm.computeIfAbsent(k, loadFunction)
      chm.get(k).void
    }
    else funit

  def preloadMany(ks: Seq[K]): Funit = ks.distinct.map(preloadOne).sequenceFu.void

  private val loadFunction = new java.util.function.Function[K, Fu[V]] {
    def apply(k: K) = {
      // println(s"*** $name chm put $k")
      compute(k).withTimeout(
        duration = resultTimeout,
        error = lila.common.LilaException(s"Syncache $name $k timed out after $resultTimeout")
      ).addEffects(
          err => {
            // println(s"*** $name chm fail $k")
            logger.branch(name).warn(s"$err key=$k")
            chm remove k
          },
          res => {
            // println(s"*** $name cache put $k")
            cache.put(k, res)
            chm remove k
          }
        )
    }
  }

  private def waitForResult(k: K, duration: FiniteDuration): V =
    Option(chm get k).fold(default(k)) { fu =>
      incWait()
      // println(s"*** $name wait $k")
      try {
        fu await duration
      }
      catch {
        case e: java.util.concurrent.TimeoutException => default(k)
      }
    }

  private val incMiss = lila.mon.syncache.miss(name)
  private val incWait = lila.mon.syncache.wait(name)
  private val incPreload = lila.mon.syncache.preload(name)
}

object Syncache {

  sealed trait Strategy

  case object NeverWait extends Strategy
  case class AlwaysWait(duration: FiniteDuration) extends Strategy
  case class WaitAfterUptime(duration: FiniteDuration, uptimeSeconds: Int = 12) extends Strategy
}
