package functionaleffects
import zio._

object BasicConcurrency extends ZIOAppDefault {

  /** Ref - functional effect equivalent of AtomicReference
    */
  // trait Ref[A] {
  //   def get: ZIO[Any, Nothing, A]
  //   def set: ZIO[Any, Nothing, Unit]
  //   def update(f: A => A): ZIO[Any, Nothing, Unit]
  //   def modify[B](f: A => (B, A)): ZIO[Any, Nothing, B]
  // }

  // object Ref {
  //   def make[A]: ZIO[Any, Nothing, Ref[A]] = ???
  // }

  /** Promise (Deferred) - "box" that starts out empty and can only be filled once Promise.make (ZIO)
    */
  // trait Promise[E, A] {
  //   def await: ZIO[Any, E, A]                  // blocking thread
  //   def succeed(a: A): ZIO[Any, Nothing, Boolean]
  //   def fail(e: E): ZIO[Any, Nothing, Boolean] // putting the failure can't fail by itself so it's nothing
  // }

  def someExpensiveComputation(n: Int): ZIO[Any, Nothing, Int] =
    ZIO.sleep(1.second) *> ZIO.succeed(n * 2)

  val inputs = List(1, 2, 3, 4, 5)

  val refRaceExample = for {
    ref <- Ref.make(0)

    // _   <- ZIO.foreachPar(inputs) { input => // running in parallel
    //          someExpensiveComputation(input).flatMap { output =>
    //            // atomic operator makes it safe even if it is inside parallel
    //            ref.update(_ + output)
    //          }
    //        }

    _ <- ZIO.foreachPar(inputs) { input =>
           someExpensiveComputation(input).flatMap { output =>
             // race condition, two fibers can take the same old value
             // we want to use only atomic operators instead of flatMaps combinations with others
             ref.get.flatMap(old => ref.set(old + output))
           }
         }

    sum <- ref.get
    _   <- ZIO.debug(sum)

  } yield ()

  def promiseWorkflow = for {
    promise <- Promise.make[Nothing, Int]
    fiber   <- (ZIO.sleep(5.seconds) *> promise.succeed(42)).fork
    // _       <- fiber.interrupt // never ends
    value <- promise.await // blocking the thread, waiting for complete
    _ <- fiber.interrupt  // does nothing since fiber is completed because of awaiting
    _ <- ZIO.debug(value) // no fiber join to complete, we just wait promise
  } yield ()

  val run = promiseWorkflow

}
