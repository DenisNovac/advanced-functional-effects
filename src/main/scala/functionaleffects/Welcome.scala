package functionaleffects

import zio._

object Welcome extends ZIOAppDefault {
  // not using any functional effect
  // println("Hello Evolution!")

  val sayHello = ZIO.succeed(println("Hello Evolution!"))

  val sayHelloFiveTimes = sayHello.repeatN(4)

  // val run = sayHello *> sayHelloFiveTimes

  /* FIBERS */

  val fiberJob = for {
    fiber <- sayHello.delay(3.second).fork               // run concurrently
    _ <- ZIO.debug("Started my computation and waiting") // it will appear immediatly
    _ <- fiber.join                                      // wait for complete
  } yield ()

  val fiberJob2 = for {
    fiber <- ZIO.succeed(42).delay(3.second).fork
    value <- fiber.join // semantically blocking fiber, not a thread
    _     <- ZIO.debug(value.toString())
    _     <- ZIO.debug("All done")

  } yield ()

  val fiberJob3 = for {
    fiber <- ZIO.succeed(42).delay(10.second).fork
    _     <- ZIO.sleep(3.second)
    exit  <- fiber.interrupt
    _     <- if (exit.isInterrupted) ZIO.debug("Took too long") else ZIO.debug("Finished")
  } yield ()

  // this won't intterrupt
  val fiberJob4 = for {
    fiber <- ZIO.succeed(while (true) println("won't stop!")).fork
    _     <- fiber.interrupt
    _     <- ZIO.debug("Exit")
  } yield ()

  // this will interrupt: forever is while(true)
  val fiberJob5 = for {
    fiber <- ZIO.succeed(println("won't stop!")).forever.fork
    _     <- fiber.interrupt
    _     <- ZIO.debug("Exit")
  } yield ()

  // this is a special operator to cancel the fiber completely (???)
  val fiberJob6 = for {
    fiber <- ZIO.attemptBlockingInterrupt(while (true) println("won't stop!")).fork
    _     <- ZIO.sleep(1.seconds)
    _     <- fiber.interrupt
    _     <- ZIO.debug("Exit")
  } yield ()

  /* RESOURCES */

  val myResource =
    ZIO.acquireReleaseWith {
      ZIO.debug("Resource acquired")
    } { _ =>
      ZIO.debug("Releasing resource")
    } { _ =>
      ZIO.debug("Using resource") *> ZIO.sleep(10.seconds)
    }

  val resourceJob = for {
    resouce <- myResource.fork
    _       <- ZIO.sleep(1.seconds)
    _ <- resouce.interrupt // resource will be released since this fork used it
    _       <- ZIO.debug("Exit")
  } yield ()

  val liar: ZIO[Any, Nothing, Int] = ZIO.succeed(
    throw new Exception("Haha")
  )

  val liar2: ZIO[Any, Throwable, Int] = ZIO.attempt(
    throw new Exception("Haha")
  )

  // fail vs defect/die errors
  // can't catch error which is not in type in ZIO!
  // val liarCatch = liar.catchAll(_ => ZIO.succeed(":)"))
  val liarCatch = liar2.catchAll(_ => ZIO.debug(":)"))

  val run = liarCatch

}
