/** ZIO provides features specifically designed to improve your experience deploying, scaling, monitoring, and
  * troubleshooting ZIO applications. These features include async stack traces, fiber dumps, logging hooks, and
  * integrated metrics and monitoring.
  *
  * In this section, you get to explore the operational side of ZIO.
  */
package advancedfunctionaleffects.ops

import zio._

import zio.test._
import zio.test.TestAspect._
import zio.metrics.Metric
import java.time.temporal.ChronoUnit

object TracesExample extends ZIOAppDefault {
  val run = ZIO.fail("fail")
}

object AsyncTraces extends ZIOSpecDefault {
  def spec =
    suite("AsyncTraces") {

      /** EXERCISE
        *
        * Pull out the `traces` associated with the following sandboxed failure, and verify there is at least one trace
        * element.
        */
      test("traces") {
        def async =
          for {
            _ <- ZIO.sleep(1.millis)
            _ <- ZIO.fail("Uh oh!")
          } yield ()

        def traces(cause: Cause[String]): List[StackTrace] =
          cause.traces

        Live.live(for {
          cause <- async.sandbox.flip
          ts     = traces(cause)
          _     <- ZIO.debug(cause.prettyPrint)
        } yield assertTrue(ts(0).stackTrace.length > 0))
      }
    }
}

object FiberDumps extends ZIOSpecDefault {
  def spec =
    suite("FiberDumps") {

      /** EXERCISE
        *
        * Compute and print out all fiber dumps of the fibers running in this test.
        */
      test("dump") {
        // we have 2 child fibers from this
        val example =
          for {
            promise <- Promise.make[Nothing, Unit]
            blocked <- promise.await.forkDaemon // it is suspended while waiting for promise
            child1  <- ZIO.foreach(1 to 100000)(_ => ZIO.unit).forkDaemon
          } yield ()

        for {
          supervisor <- Supervisor.track(false)
          // monitoring any fibers in example workflow with supervisor
          _          <- example.supervised(supervisor)

          // all fibers that were forked in example workflow
          children <- supervisor.value
          _        <- ZIO.foreach(children)(child => child.dump.flatMap(_.prettyPrint.flatMap(ZIO.debug(_))))
          // _        <- Fiber.dumpAll - just print every fiber
        } yield assertTrue(children.length == 2)
      } @@ flaky
    }

  /** "zio-fiber-73" (6ms) waiting on #24 Status: Suspended((Interruption, CooperativeYielding, FiberRoots),
    * advancedfunctionaleffects.ops.FiberDumps.spec.example(08-operations.scala:59)) at
    * advancedfunctionaleffects.ops.FiberDumps.spec.example(08-operations.scala:59)
    *
    * "zio-fiber-74" (18ms) Status: Running((Interruption, CooperativeYielding, FiberRoots), <no trace>) at
    * advancedfunctionaleffects.ops.FiberDumps.spec.example(08-operations.scala:60)
    */
}

// ZIO provides "front end"
// Consistent interface that you use for logging and metrics (ZIO Core)

// Specialized libs are going to provide specific backend implementations
// that do something with that information for log/mtrics

// ZIO Logging (connecters to log4j, logback, etc...)
// ZIO Metrics (prometheus, etc...)

object ExampleApp extends ZIOAppDefault {

  // backends installed as layers
  val prometheusMetricsBackend: ZLayer[Any, Nothing, Unit] = ???
  val log4jBackend: ZLayer[Any, Nothing, Unit]             = ???

  // layer for configuration the app
  override val bootstrap: ZLayer[ZIOAppArgs with Scope, Any, Any] =
    prometheusMetricsBackend ++ log4jBackend

  val run = ???
}

/** logging frontend in zio:
  *   - Log statements
  *   - Log levels
  *   - Log spans
  *   - Log annotation
  */
object LoggingExample extends ZIOAppDefault {

  val loudLogger =
    ZLogger.default.map(_.toUpperCase()).map(println)

  override val bootstrap =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(loudLogger) // now there are two loggers since we "add" loggers

  val run =
    ZIO.logAnnotate("key", "value") {
      ZIO.logSpan("my span") { // span provides a timing
        ZIO.logLevel(LogLevel.Warning) {
          ZIO.log("Hello")
        }
      }
    }

}

object Logging extends ZIOSpecDefault {
  def spec =
    suite("Logging")()
}

/** ZIO Core defines a "common language" for how we specify that we want to track something as a metric
  *
  * ZIO Metrics Connectors provides the specific backend implementations
  */

object MetricsExample extends ZIOAppDefault {

  val metric = Metric.counter("my-counter")

  // metrics have aspects
  val executionTime = Metric.timer("my-timer", ChronoUnit.SECONDS)

  val aspectsWorkflow =
    ZIO.debug("Hello") @@ metric.trackAll(1L)

  val executionTimeAspect =
    executionTime.trackDuration

  val longWorkflow =
    ZIO.sleep(10.millis) @@ executionTimeAspect

  val run = for {
    _ <- metric.increment
    _ <- aspectsWorkflow

    _ <- longWorkflow
    _ <- longWorkflow

    state <- metric.value
    _     <- ZIO.debug(state)

    time <- executionTime.value
    _    <- ZIO.debug(time)
  } yield ()
}

object Metrics extends ZIOSpecDefault {
  def spec =
    suite("Metrics")()
}
