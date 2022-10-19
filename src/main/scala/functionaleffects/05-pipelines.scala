/** In ZIO Stream, pipelines transform 1 or more values of one type to 1 or more values of another type. They are
  * typically used for encoding, decoding, compression, decompression, rechunking, encryption, decryption, and other
  * similar element transformations.
  */
package advancedfunctionaleffects.pipelines

import zio._
import zio.stream._

import zio.test._
import zio.test.TestAspect._
import java.nio.charset.StandardCharsets

object Introduction extends ZIOSpecDefault {
  def spec =
    suite("Introduction") {

      /** EXERCISE
        *
        * Use `>>>` to append a pipeline to a stream, transforming the elements of the stream by the pipeline.
        */
      test(">>>") {
        val stream = ZStream.range(1, 10)
        val trans  = ZPipeline.take[Int](5)
        val sink   = ZSink.collectAll[Int]

        for {
          chunks <- (stream >>> trans >>> sink)
        } yield assertTrue(chunks.length == 5)
      } +
        /** EXERCISE
          *
          * Using `.via`, transform the elements of this stream by the provided pipeline.
          */
        test("via") {
          val stream = ZStream.range(1, 100)
          val trans  = ZPipeline.take[Int](10)

          for {
            values <- stream.via(trans).runCollect
          } yield assertTrue(values.length == 10)
        }
    }
}

object TransduceExample extends ZIOAppDefault {

  val stream = ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  val sink   = ZSink.collectAllN[Int](2)

  // stream.run(sink).debug // Chunk(1,2)

  // stream.transduce(sink).debug.runDrain
  // transduce will repeat sink on the sink:
  // Chunk(1, 2)
  // Chunk(3, 4)
  // Chunk(5, 6)
  // Chunk(7, 8)
  // Chunk(9, 10)
  // Chunk()

  val pipeline = ZPipeline.fromSink(sink)

  // the same as transduce, just repeating sink (originally sink works only once)
  val run = (stream >>> pipeline).debug.runDrain
}

object Constructors extends ZIOSpecDefault {
  def spec =
    suite("Constructors") {

      /** EXERCISE
        *
        * Using `ZPipeline.splitLines` transform the stream elements so they are split on newlines.
        */
      test("splitLines") {
        val stream = ZStream("a\nb\nc\n", "d\ne")

        for {
          values <- stream.via(ZPipeline.splitLines).runCollect
        } yield assertTrue(values == Chunk("a", "b", "c", "d", "e"))
      } +
        /** EXERCISE
          *
          * Using `ZPipeline.splitOn(",")`, transform the stream elements so they are split on newlines.
          */
        test("splitOn") {
          val stream = ZStream("name,age,add", "ress,dob,gender")

          for {
            values <- stream.via(ZPipeline.splitOn(",")).runCollect
          } yield assertTrue(values == Chunk("name", "age", "address", "dob", "gender"))
        } +
        /** EXERCISE
          *
          * Using `ZPipeline.utf8Decode`, transform the stream elements so they are UTF8 decoded.
          */
        test("utf8Decode") {
          val bytes1 = "Hello".getBytes(StandardCharsets.UTF_8)
          val bytes2 = "World!".getBytes(StandardCharsets.UTF_8)

          val stream = ZStream.fromChunks(Chunk.fromArray(bytes1), Chunk.fromArray(bytes2))

          def decodedStream: ZStream[Any, Nothing, String] =
            stream >>> ZPipeline.utf8Decode.orDie >>> ZPipeline.filter(_.nonEmpty)

          for {
            values <- decodedStream.runCollect
          } yield assertTrue(values == Chunk("Hello", "World!"))
        } +
        /** EXERCISE
          *
          * Using `ZPipeline.map`, create a pipeline that converts strings to ints.
          */
        test("map") {
          def parseInt: ZPipeline[Any, Nothing, String, Int] =
            ZPipeline.map(_.toInt)

          val stream = ZStream("1", "2", "3")

          for {
            values <- (stream >>> parseInt).runCollect
          } yield assertTrue(values == Chunk(1, 2, 3))
        }
    }
}

object Operators extends ZIOSpecDefault {
  def spec =
    suite("Operators") {

      /** EXERCISE
        *
        * Use `>>>` to compose `utf8Encode` and `utf8Decode`, and verify this composition doesn't change the string
        * stream.
        */
      test(">>>") {
        import ZPipeline.utf8Decode

        def utf8Encode: ZPipeline[Any, Nothing, String, Byte] =
          ZPipeline
            .collect[String, Chunk[Byte]] { case string =>
              Chunk.fromArray(string.getBytes(StandardCharsets.UTF_8))
            } >>> ZPipeline.mapChunks(_.flatten)

        val toChar: ZPipeline[Any, Nothing, String, Char] =
          ZPipeline.mapChunks(chunk => chunk.flatMap(s => s))

        val splitWords = {
          import zio.stream.ZChannel

          // AllWorkAndNoPlayMakesJackADullBoy
          // this channel will get merged string, divide by upper case
          def channel(
              leftovers: Chunk[Char]
          ): ZChannel[Any, ZNothing, Chunk[String], Any, ZNothing, Chunk[String], Any] =
            ZChannel.readWith(
              in => {
                val chars                 = leftovers ++ in.flatten
                val (words, leftoversNew) = extractWords(chars)
                ZChannel.write(words) *> channel(leftovers)
              },
              err => ZChannel.fail(err),
              done => ZChannel.succeed(done)
            )

          // wtf...
          def extractWords(chars: Chunk[Char]): (Chunk[String], Chunk[Char]) = {
            val iterator         = chars.iterator
            val wordBuilder      = ChunkBuilder.make[String]()
            val leftoversBuilder = ChunkBuilder.make[Char]()

            var started       = false
            var stringBuilder = new StringBuilder

            while (iterator.hasNext) {
              val char = iterator.next()
              if (char.isUpper && started) {
                val string = stringBuilder.toString()
                wordBuilder += string
                stringBuilder = new StringBuilder
                stringBuilder += char
              } else {
                started = true
                stringBuilder += char
              }
            }
            wordBuilder += stringBuilder.toString()
            (wordBuilder.result(), leftoversBuilder.result())
          }

          ZPipeline.fromChannel(channel(Chunk.empty))
        }

        def composed = utf8Encode >>>
          utf8Decode >>>
          splitWords >>>
          ZPipeline.filter[String](_.nonEmpty)

        val chunk = Chunk("All", "Work", "And", "No", "Play", "Makes", "Jack", "A", "Dull", "Boy")

        val stream = ZStream.fromChunk(chunk)

        for {
          values <- (stream >>> composed).runCollect
        } yield assertTrue(values == chunk)
      }
    }
}

/** GRADUATION
  *
  * To graduate from this section, you will implement a pipeline that rechunks a stream.
  */
object Graduation extends ZIOSpecDefault {
  def rechunkWith[A](f: (Chunk[A], Chunk[A]) => (Chunk[A], Chunk[A])): ZPipeline[Any, Nothing, A, A] =
    ???

  def rechunk[A](n: Int): ZPipeline[Any, Nothing, A, A] =
    rechunkWith { case (leftover, next) =>
      (leftover ++ next).splitAt(n + 1)
    }

  def spec =
    suite("Graduation") {
      test("rechunking") {
        val stream = ZStream.fromChunks(Chunk(1), Chunk(2, 3, 4), Chunk(5), Chunk(6, 7, 8))

        for {
          values <- (stream >>> rechunk[Int](2)).mapChunks(c => Chunk(c)).runCollect
        } yield assertTrue(values == Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6)))
      } @@ ignore
    }
}
