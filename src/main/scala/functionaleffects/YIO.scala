package functionaleffects

// ZIO[R, E, A]
// R == services required
// E == how the worflow can fail
// A == succeed

// Cats IO[A] = ZIO[Any, Throwable, A]
// Monix Task[A] == ZIO[Any, Throwable, A]

// Your IO
trait YIO[+A] { self =>
  import YIO._

  def flatMap[B](f: A => YIO[B]): YIO[B] =
    FlatMap(self, f)

  def *>[B](that: YIO[B]): YIO[B] =
    zipRight(that)

  def map[B](f: A => B): YIO[B] =
    flatMap(a => succeed(f(a)))

  def repeatN(n: Int): YIO[Unit] =
    if (n <= 0) unit
    else self *> repeatN(n - 1)

  def zipWith[B, C](that: YIO[B])(f: (A, B) => C): YIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a, b)

  def zipRight[B](that: YIO[B]): YIO[B] =
    zipWith(that)((_, b) => b)

  def unsafeRun(): Unit = {
    val fiber = RuntimeFiber(self)
    fiber.unsafeRun
  }
}

object YIO {
  def succeed[A](value: A): YIO[A] =
    Succeed(() => value) // lazy evaluation - zero-args function

  val unit: YIO[Unit] = succeed(())

  // primitives for effects
  final case class FlatMap[A, B](first: YIO[A], andThen: A => YIO[B]) extends YIO[B]
  final case class Succeed[A](value: () => A)                         extends YIO[A]
}

trait Fiber[+A] {
  def unsafeRun(): A

}

object Fiber {
  def apply[A](yio: YIO[A]): Fiber[A] =
    ???
}

final case class RuntimeFiber[+A](yio: YIO[A]) extends Fiber[A] {

  type ErasedYIO          = YIO[Any]
  type ErasedContinuation = Any => YIO[Any]

  private var currentYIO: ErasedYIO = yio

  // stack of continuations
  // result of previous to a new one
  private val stack = scala.collection.mutable.Stack[ErasedContinuation]()

  def unsafeRun(): A = {
    var loop      = true
    var result: A = null.asInstanceOf[A]

    // interruptions might be called between each step of those loops
    while (loop)
      currentYIO match {
        case YIO.Succeed(value) =>
          // we know it's A because we created this language with FlatMaps and everything
          // we losing type information just like Java in runtime
          val computedValue = value()
          if (stack.isEmpty) {
            loop = false
            result = computedValue.asInstanceOf[A]
          } else {
            val nextContinuation = stack.pop()
            currentYIO = nextContinuation(computedValue)
          }

        case YIO.FlatMap(first, andThen) =>
          currentYIO = first
          stack.push(andThen)
      }

    result

  }
}

object Example extends App {

  val sayHello = YIO.succeed(println("Hello Evolution!"))

  val sayHelloFiveTimes =
    sayHello.repeatN(5)

  sayHelloFiveTimes.unsafeRun()
}
