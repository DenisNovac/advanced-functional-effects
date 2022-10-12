package functionaleffects

import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec

// ZIO[R, E, A]
// R == services required
// E == how the worflow can fail
// A == succeed

// Cats IO[A] = ZIO[Any, Throwable, A]
// Monix Task[A] == ZIO[Any, Throwable, A]

// Your IO
trait YIO[+A] { self =>
  import YIO._

  def *>[B](that: YIO[B]): YIO[B] =
    zipRight(that)

  def flatMap[B](f: A => YIO[B]): YIO[B] =
    FlatMap(self, f)

  def fork: YIO[Fiber[A]] =
    YIO.succeed {
      val fiber = RuntimeFiber(self)
      fiber.start()
      fiber
    }

  def map[B](f: A => B): YIO[B] =
    flatMap(a => succeed(f(a)))

  def repeatN(n: Int): YIO[Unit] =
    if (n <= 0) unit
    else self *> repeatN(n - 1)

  def unsafeRunAsync(): Unit = {
    val fiber = RuntimeFiber(self)
    fiber.start()
  }

  def unsafeRunSync(): A = {
    var result: A = null.asInstanceOf[A]
    val latch     = new java.util.concurrent.CountDownLatch(1)
    self
      .flatMap { a =>
        YIO.succeed {
          result = a
          latch.countDown()
        }
      }
      .unsafeRunAsync()
    latch.await()
    result
  }

  def zipRight[B](that: YIO[B]): YIO[B] =
    zipWith(that)((_, b) => b)

  def zipWith[B, C](that: YIO[B])(f: (A, B) => C): YIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def zipWithPar[B, C](that: YIO[B])(f: (A, B) => C): YIO[C] =
    for {
      left  <- self.fork
      right <- that.fork
      a     <- left.join
      b     <- right.join
    } yield f(a, b)

  def collectAll[A](yios: List[YIO[A]]): YIO[List[A]] =
    ???

  def collectAllPar[A](yios: List[YIO[A]]): YIO[List[A]] =
    ???

  def foreach[A, B](as: List[A])(f: A => YIO[B]): YIO[List[B]] =
    ???

  def foreachPar[A, B](as: List[A])(f: A => YIO[B]): YIO[List[B]] =
    ???
}

object YIO {
  // register gives callback which we can provide
  def async[A](register: (YIO[A] => Unit) => Any): YIO[A] =
    Async(register)

  def succeed[A](value: A): YIO[A] =
    Succeed(() => value) // lazy evaluation - zero-args function

  val unit: YIO[Unit] = succeed(())

  // primitives for effects
  final case class FlatMap[A, B](first: YIO[A], andThen: A => YIO[B]) extends YIO[B]
  final case class Succeed[A](value: () => A)                         extends YIO[A]
  final case class Async[A](register: (YIO[A] => Unit) => Any)        extends YIO[A]
}

sealed trait Promise[A] {
  def await: YIO[A]
  def succeed(a: A): YIO[Boolean]
}

object Promise {

  def make[A]: YIO[Promise[A]] =
    YIO.succeed {
      val state = new java.util.concurrent.atomic.AtomicReference[State[A]](Empty(Nil))

      new Promise[A] {

        def await: YIO[A] =
          YIO.async { cb =>
            var loop = true
            while (loop) {
              val currentState = state.get()

              currentState match {
                case Done(a) =>
                  loop = false
                  cb(YIO.succeed(a))

                case Empty(callbacks) =>
                  if (state.compareAndSet(currentState, Empty(cb :: callbacks))) {
                    loop = false
                  }
              }
            }
          }

        def succeed(a: A): YIO[Boolean] =
          YIO.succeed {
            var loop   = true
            var result = false
            while (loop) {
              val currentState = state.get()
              currentState match {
                case Done(_) =>
                  loop = false

                case Empty(joiners) =>
                  if (state.compareAndSet(currentState, Done(a))) {
                    joiners.foreach(_(YIO.succeed(a)))
                    result = true
                    loop = false
                  }
              }
            }
            result
          }
      }
    }

    sealed trait State[A]
    final case class Done[A](value: A)                       extends State[A]
    final case class Empty[A](joiners: List[YIO[A] => Unit]) extends State[A]

}

trait Fiber[+A] {
  def join: YIO[A]
}

final case class RuntimeFiber[A](yio: YIO[A]) extends Fiber[A] {

  private val executor = scala.concurrent.ExecutionContext.global

  type ErasedYIO          = YIO[Any]
  type ErasedContinuation = Any => YIO[Any]

  private var currentYIO: ErasedYIO = yio

  // stack of continuations
  // result of previous to a new one
  private val stack = scala.collection.mutable.Stack[ErasedContinuation]()

  // actor-like fibers have inbox
  private val inbox =
    new java.util.concurrent.ConcurrentLinkedQueue[FiberMessage]

  private val running: AtomicBoolean = new AtomicBoolean(false)

  private val observers =
    scala.collection.mutable.Set[YIO[Any] => Unit]()

  private var exit: A =
    null.asInstanceOf[A]

  private def offerToInbox(fiberMessage: FiberMessage): Unit = {
    inbox.offer(fiberMessage)

    if (running.compareAndSet(false, true)) {
      drainQueueOnExecutor()
    }
  }

  private def drainQueueOnExecutor(): Unit =
    executor.execute(() => drainQueueOnCurrentThread())

  @tailrec
  private def drainQueueOnCurrentThread(): Unit = {
    var fiberMessage = inbox.poll()

    while (fiberMessage != null) {
      processFiberMessage(fiberMessage)
      fiberMessage = inbox.poll()
    }

    running.set(false)

    // if in between someone else added something
    if (!inbox.isEmpty) {
      if (running.compareAndSet(false, true)) {
        drainQueueOnCurrentThread()
      }
    }

  }

  // guaranteed to be single-threaded
  private def processFiberMessage(fiberMessage: FiberMessage): Unit =
    fiberMessage match {
      case FiberMessage.Resume(yio) =>
        currentYIO = yio
        runLoop()

      case FiberMessage.Start =>
        runLoop()

      case FiberMessage.AddObserver(observer) =>
        if (exit == null) {
          observers.add(observer)
        } else {
          observer(YIO.succeed(exit))
        }
    }

  def start(): Unit =
    offerToInbox(FiberMessage.Start)

  override def join: YIO[A] =
    YIO.async { cb =>
      offerToInbox(FiberMessage.AddObserver(cb.asInstanceOf[YIO[Any] => Unit]))
    }

  private def runLoop(): Unit = {
    var loop = true

    // interruptions might be called between each step of those loops
    while (loop)
      currentYIO match {
        case YIO.Succeed(value) =>
          // we know it's A because we created this language with FlatMaps and everything
          // we losing type information just like Java in runtime
          val computedValue = value()
          if (stack.isEmpty) {
            exit = computedValue.asInstanceOf[A]
            observers.foreach(_(YIO.succeed(computedValue)))
            loop = false
          } else {
            val nextContinuation = stack.pop()
            currentYIO = nextContinuation(computedValue)
          }

        case YIO.FlatMap(first, andThen) =>
          currentYIO = first
          stack.push(andThen)

        // we want to stop loop
        // then we restart the loop with register
        // actor-based encoding of fibers
        case YIO.Async(register) =>
          currentYIO = null
          loop = false
          register(yio => offerToInbox(FiberMessage.Resume(yio)))
      }
  }

  sealed trait FiberMessage
  object FiberMessage {
    // when YIO is done - whoever done it should send us a message about it
    final case class AddObserver(cb: YIO[Any] => Unit) extends FiberMessage
    final case class Resume(yio: YIO[Any])             extends FiberMessage
    final case object Start                            extends FiberMessage
  }
}

object Example extends App {

  val sayHello = YIO.succeed(println("Hello Evolution!"))

  val sayHelloFiveTimes =
    sayHello.repeatN(5)

  sayHelloFiveTimes.unsafeRunAsync()

  val left = YIO.succeed {
    Thread.sleep(5000)
    "left"
  }

  val right = YIO.succeed {
    Thread.sleep(5000)
    "right"
  }

  val parallel = for {
    fiber1     <- left.fork
    fiber2     <- right.fork
    leftValue  <- fiber1.join
    rightValue <- fiber2.join
    _          <- YIO.succeed(println(leftValue, rightValue))
  } yield ()

  parallel.unsafeRunSync()

}
