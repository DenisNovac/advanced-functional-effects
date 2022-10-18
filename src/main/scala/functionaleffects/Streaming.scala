package functionaleffects

import zio._

object Streaming {

  // Why do we need streams?
  // How are streams different from other functional effects?

  // ZIO[R, E, A]
  // Workflow that will either fail with E or succeed with exactly one A

  // ZIO[R, E, List[A]]
  // it will still have two states - nothing or all at once

  // ZIOs or other functional effects are "binary" - they either have no result or have the full result

  // ZStream[R, E, A] - either fail or succeed with zero or more values
  // potentially have infinite A values

  // Inputs (new transactions, new infer from buisiness pratners ) SOURCE
  // >>> Processing PIPELINE
  // >>> Outputs SINK
  // >>> PIPE OPERATOR (ZStream)
  // in ZIO2 there is only ZChannel that makes any of this

  // Stream - effectual iterator (it was like this literally in ZIO 1, but not in ZIO 2)

  // Iterators can describe infinite lazy collections by itself (LazyList, etc)
  trait Iterable[A] {
    def iterator: Iterator[A]
  }

  trait Iterator[A] {
    def hasNext: Boolean
    def next(): A // side effect - processes the state of this Iterator
  }

  // not purely functional interface (wrapped in ZIO - done)
  // not handles asynchorny
  // no resource safety

  object Iterator {
    def make[A](): Iterator[A] = ??? // not a transparent method, we allocate new state each time
  }

  // def next(): ZIO[Any, Nothing, Option[A]]
  // I i evaluate next and get A - there is value and may be more
  // if i get a None - there is no more elements and will not be

  // def next(): ZIO[R, E, Option[A]]
  // now we have value, E = error or E = None (end of stream signal)
  trait ZIterator[-R, +E, +A] {
    def next(): ZIO[R, Option[E], A]
  }

  object ZIterator {
    def make[R, E, A]: ZIO[Scope, Nothing, ZIterator[R, E, A]] =
      ???
  }

  // stream in ZIO 1 was like this
  final case class ZStream[R, E, A](pull: ZIO[Scope, Nothing, ZIO[R, Option[E], Chunk[A]]]) {
    def map[B](f: A => B): ZStream[R, E, B] =
      ZStream(pull.map(_.map(_.map(f)))) // every time i poll i transform chunks with function f
  }

  // outer ZIO is opening and closing the stream
  // inner ZIO is pulling the next value from the stream

  /** workflow construction example with streams */

  // sinks and pipelines can describe streams parts as values
  // which makes them more composible
  trait NewTransaction
  trait AnalysisResult
  val infoFromBuisinessPartners: zio.stream.ZStream[Any, Nothing, NewTransaction]          = ???
  val analysisPipeline: zio.stream.ZPipeline[Any, Nothing, NewTransaction, AnalysisResult] = ???
  val databaseWriter: zio.stream.ZSink[Any, Nothing, AnalysisResult, Nothing, Unit]        = ???

  // additional logging service
  val loggingSink: zio.stream.ZSink[Any, Nothing, AnalysisResult, Nothing, Unit] = ???

  val myWorkflow: ZIO[Any, Nothing, Unit] =
    infoFromBuisinessPartners >>>
      analysisPipeline >>>
      (databaseWriter.zipPar(loggingSink)) // we can combine sinks

}

// common mistake - "fake stream":
object FakeStreamExample extends ZIOAppDefault {
  import zio.stream.{ZStream => LibZStream}

  lazy val stream: LibZStream[Any, Nothing, Int] =
    LibZStream.from(List(1, 2, 3))

  // fromZIO will create a single-element stream because ZIO is one-element effect
  val stream2: LibZStream[Any, Nothing, Int] =
    LibZStream.fromZIO(stream.runFold(0)(_ + _))

    // won't terminate if infinite stream
    // won't produce any element until internal stream completes. It waits for the internal stream

    // right implementation without waiting for stream completion:
    val workflow =
      Ref.make(0).flatMap { r =>
        {
          for {
            x <- stream
            _ <- LibZStream.fromZIO(r.update(i => i + x))
          } yield ()
        }.runDrain *> r.get.flatMap(ZIO.debug(_))
      }

    val run = workflow

}
