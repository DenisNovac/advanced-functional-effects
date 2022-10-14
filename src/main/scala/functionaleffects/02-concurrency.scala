/** CONCURRENCY
  *
  * ZIO has pervasive support for concurrency, including parallel versions of operators like `zip`, `foreach`, and many
  * others.
  *
  * More than just enabling your applications to be highly concurrent, ZIO gives you lock-free, asynchronous structures
  * like queues, hubs, and pools. Sometimes you need more: you need the ability to concurrently update complex shared
  * state across many fibers.
  *
  * Whether you need the basics or advanced custom functionality, ZIO has the toolset that enables you to solve any
  * problem you encounter in the development of highly-scalable, resilient, cloud-native applications.
  *
  * In this section, you will explore more advanced aspects of ZIO concurrency, so you can learn to build applications
  * that are low-latency, highly- scalable, interruptible, and free from deadlocks and race conditions.
  */
package advancedfunctionaleffects.concurrency

import zio._
import zio.stm._
import zio.test._
import zio.test.TestAspect._

/** ZIO queues are high-performance, asynchronous, lock-free structures backed by hand-optimized ring buffers. ZIO
  * queues come in variations for bounded, which are doubly-backpressured for producers and consumers, sliding (which
  * drops earlier elements when capacity is reached), dropping (which drops later elements when capacity is reached),
  * and unbounded.
  *
  * Queues work well for multiple producers and multiple consumers, where consumers divide work between themselves.
  */
object QueueBasics extends ZIOSpecDefault {
  def spec =
    suite("QueueBasics") {

      /** EXERCISE
        *
        * Using `.take` and `.offer`, create two fibers, one which places 12 inside the queue, and one which takes an
        * element from the queue and stores it into the provided `ref`.
        */
      test("offer/take") {
        for {
          ref   <- Ref.make(0)
          queue <- Queue.bounded[Int](16)
          // if we reomve both forks - the order will matter but not now
          fiber <- queue.take.flatMap(ref.set).fork // take will wait for queue anyway so we could run it and fork it before putting the value
          _     <- Live.live(ZIO.sleep(1.second)) *> queue.offer(12).fork
          _     <- fiber.join
          v     <- ref.get
        } yield assertTrue(v == 12)
      } +
        /** EXERCISE
          *
          * Create a consumer of this queue that adds each value taken from the queue to the counter, so the unit test
          * can pass.
          */
        test("consumer") {
          for {
            counter <- Ref.make(0)
            queue   <- Queue.bounded[Int](100)
            _       <- ZIO.foreach(1 to 100)(v => queue.offer(v)).fork
            // no need to join since we are waiting for 100 elements in queue in take
            _       <- queue.takeN(100).flatMap(as => counter.update(_ => as.sum))
            value   <- counter.get
          } yield assertTrue(value == 5050)
        } +
        /** EXERCISE
          *
          * Queues are fully concurrent-safe on both producer and consumer side. Choose the appropriate operator to
          * parallelize the production side so all values are produced in parallel.
          */
        test("multiple producers") {
          for {
            counter <- Ref.make(0)
            queue   <- Queue.bounded[Int](100)
            _       <- ZIO.foreachPar(1 to 100)(v => queue.offer(v)).forkDaemon
            // because of foreachPar and fork we are starting to take before foreach is done
            _       <- queue.take.flatMap(v => counter.update(_ + v)).repeatN(99)
            value   <- counter.get
          } yield assertTrue(value == 5050)
        } +
        /** EXERCISE
          *
          * Choose the appropriate operator to parallelize the consumption side so all values are consumed in parallel.
          */
        test("multiple consumers") {
          for {
            counter <- Ref.make(0)
            queue   <- Queue.bounded[Int](100)
            _       <- ZIO.foreachPar(1 to 100)(v => queue.offer(v)).forkDaemon
            // every take will wait for value in parallel
            _       <- ZIO.foreachPar(1 to 100)(_ => queue.take.flatMap(v => counter.update(_ + v)))
            value   <- counter.get
          } yield assertTrue(value == 5050)
          // no race condition in Queue
        } @@ nonFlaky +
        /** EXERCISE
          *
          * Shutdown the queue, which will cause its sole producer to be interrupted, resulting in the test succeeding.
          */
        test("shutdown") {
          for {
            done   <- Ref.make(false)
            latch  <- Promise.make[Nothing, Unit]
            queue  <- Queue.bounded[Int](100)
            _      <- (latch.succeed(()) *> queue.offer(1).forever).ensuring(done.set(true)).fork
            _      <- latch.await
            _      <- queue.takeN(100)
            _ <- queue.shutdown // shutdown will make queue.offer fail and then call ensuring
            isDone <- done.get.repeatWhile(_ == false).timeout(10.millis).some
          } yield assertTrue(isDone)
        }
    }
}

/** Ref:
  *   - can't compose atomic updates
  *   - can't wait for some condition
  *
  * Promise:
  *   - can only be completed with exactly one value
  *
  * Ref[Promise[E, A]]
  *
  * It might be more simple just to use STM. It trade off prefomance for developer productivity.
  *
  * STM might be slower than Promise or Ref.
  *
  * STM is a journal that holding up all variables in transaction. And at the very end we will commit transaction. If
  * some variables modified - it will retry the transaction. They helping some concurrencty problems.
  */

object STMExample extends ZIOAppDefault {

  def transfer(from: Ref[Int], to: Ref[Int]): ZIO[Any, Nothing, Unit] =
    for {
      // how to do this correctly so nobody change ref between?
      _ <- from.update(_ - 1)
      _ <- to.update(_ + 1)
    } yield ()

    val program =
      for {
        ref1 <- Ref.make(100)
        ref2 <- Ref.make(0)
        _    <- ZIO.collectAll(List.fill(100)(transfer(ref1, ref2))).unit

      } yield ()

    val run = ???
}

/** ZIO's software transactional memory lets you create your own custom lock-free, race-free, concurrent structures, for
  * cases where there are no alternatives in ZIO, or when you need to make coordinated changes across many structures in
  * a transactional way.
  */
object StmBasics extends ZIOSpecDefault {
  def spec =
    suite("StmBasics") {
      test("latch") {

        // Promise[Nothing, Unit] - we used this latch before to await something without anything inside

        /** EXERCISE
          *
          * Implement a simple concurrent latch.
          */
        final case class Latch(ref: TRef[Boolean]) {

          def await: UIO[Any] =
            // transactional ref made to work with STM
            ref.get.flatMap { completed =>
              if (completed) STM.unit
              // invalid state, needs to be retried
              // it will only retry only if some variable inside transaction changed
              // suspend until condition satisfied
              else STM.retry
            }.commit

          def trigger: UIO[Any] =
            ref.set(true).commit
        }

        def makeLatch: UIO[Latch] = TRef.make(false).map(Latch(_)).commit

        for {
          latch  <- makeLatch
          waiter <- latch.await.fork
          _      <- Live.live(Clock.sleep(10.millis))
          first <- waiter.poll // checks if fiber is complete
          _      <- latch.trigger
          _      <- Live.live(Clock.sleep(10.millis))
          second <- waiter.poll
        } yield assertTrue(first.isEmpty && second.isDefined)
      } +
        test("countdown latch") {

          /** EXERCISE
            *
            * Implement a simple concurrent latch.
            */
          final case class CountdownLatch(ref: TRef[Int]) {

            def await: UIO[Int] =
              ref.get.flatMap { int =>
                if (int == 0) STM.succeed(int)
                else STM.retry
              }.commit

            def countdown: UIO[Any] = ref.update(i => i - 1).commit
          }

          def makeLatch(n: Int): UIO[CountdownLatch] = TRef.make(n).map(ref => CountdownLatch(ref)).commit

          for {
            latch  <- makeLatch(10)
            _      <- latch.countdown.repeatN(8)
            waiter <- latch.await.fork
            _      <- Live.live(Clock.sleep(10.millis))
            first  <- waiter.poll
            _      <- ZIO.debug(first)
            _      <- latch.countdown
            _      <- Live.live(Clock.sleep(10.millis))
            second <- waiter.poll
            _      <- ZIO.debug(second)
          } yield assertTrue(first.isEmpty && second.isDefined)
        } +
        test("permits") {

          /** EXERCISE
            *
            * Implement `acquire` and `release` in a fashion the test passes.
            */
          final case class Permits(ref: TRef[Int]) {
            private def acquire(howMany: Int): UIO[Unit] =
              ref.get.flatMap { available =>
                if (available >= howMany) ref.set(available - howMany)
                else STM.retry
              }.commit

            private def release(howMany: Int): UIO[Unit] =
              ref.update(_ + howMany).commit

            // automatically release permits after zio is done so no manual release needed
            def withPermits[R, E, A](howMany: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
              ZIO.acquireReleaseWith(acquire(howMany))(_ => release(howMany))(_ => zio)
          }

          def makePermits(max: Int): UIO[Permits] = TRef.make(max).map(Permits(_)).commit

          for {
            counter <- Ref.make(0)
            permits <- makePermits(100)
            _       <-
              ZIO.foreachPar(1 to 1000)(_ => Random.nextIntBetween(1, 2).flatMap(n => permits.withPermits(n)(ZIO.unit)))
            latch   <- Promise.make[Nothing, Unit]
            fiber   <- (latch.succeed(()) *>
                         permits.withPermits(101)(counter.set(1))).forkDaemon
            _       <- latch.await
            _       <- Live.live(ZIO.sleep(1.second))
            _       <- fiber.interrupt
            count   <- counter.get
            permits <- permits.ref.get.commit
          } yield assertTrue(count == 0 && permits == 100)
        }
    }
}

/** ZIO hubs are high-performance, asynchronous, lock-free structures backed by hand-optimized ring buffers. Hubs are
  * designed for broadcast scenarios where multiple (potentially many) consumers need to access the same values being
  * published to the hub.
  */
object HubBasics extends ZIOSpecDefault {
  def spec =
    suite("HubBasics") {

      /** EXERCISE
        *
        * Use the `subscribe` method from 100 fibers to pull out the same values from a hub, and use those values to
        * increment `counter`.
        *
        * Take note of the synchronization logic. Why is this logic necessary?
        */
      test("subscribe") {
        for {
          counter <- Ref.make[Int](0)
          hub     <- Hub.bounded[Int](100)
          latch   <- TRef.make(100).commit
          scount  <- Ref.make[Int](0)
          _       <- (latch.get.retryUntil(_ <= 0).commit *> ZIO.foreach(1 to 100)(hub.publish(_))).forkDaemon
          _       <- ZIO.foreachPar(1 to 100) { _ =>
                       ZIO.scoped(hub.subscribe.flatMap { queue =>
                         latch.update(_ - 1).commit *> queue.takeN(100)
                       })
                     }
          value   <- counter.get
        } yield assertTrue(value == 505000)
      }
    }
}

/** GRADUATION PROJECT
  *
  * To graduate from this section, you will choose and complete one of the following two problems:
  *
  *   - Implement a bulkhead pattern, which provides rate limiting to a group of services to protect other services
  *     accessing a given resource.
  *   - Implement a CircuitBreaker, which triggers on too many failures, and which (gradually?) resets after a certain
  *     amount of time.
  */
object Graduation extends ZIOSpecDefault {
  def spec =
    suite("Graduation")()
}

trait WebService {
  def get(id: Int): UIO[Int]
  def put(id: Int, value: String): UIO[Int]
}

/** Question we might ask ourselfs:
  *   - What errors "count" towards the CB?
  *   - Do we want to have states (open, closed, etc) OR
  *   - some concept of "continious state"
  */
trait CircuitBreaker {
  def apply[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A]
}

object CircuitBreaker {

  def make: ZIO[Any, Nothing, CircuitBreaker] =
    ???
}

object ExampleUsage {

  val myWebService: WebService = ???

  val run = for {
    cb <- CircuitBreaker.make
    ws  = new WebService {
            override def get(id: Int): UIO[Int]                = cb(myWebService.get(id))
            override def put(id: Int, value: String): UIO[Int] = cb(myWebService.put(id, value))
          }
  } yield ()
}
