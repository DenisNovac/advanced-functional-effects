package functionaleffects

import zio._

object FiberSupervision extends ZIOAppDefault {

  val workflow: URIO[Any, Fiber.Runtime[Nothing, Fiber.Runtime[Nothing, Unit]]] =
    (ZIO.sleep(5.seconds) *> ZIO.debug("Time to wake up")).fork.fork

  val run =
    for {
      _ <- workflow
      _ <- ZIO.sleep(10.seconds)
    } yield ()

  // in ZIO there is FiberSupervision (structured concurrency)
  // parent actor supervise it's children. When it's done it terminates chlidren.
  // ZIO does the same with Fork
  // When fiber completes execution before completeing wind down it will interrupt all children

  // forkDaemon - without supervision (for true background process use this!)

  // CE and Monix do not have this
  // CE start = ZIO forkDaemon
}
