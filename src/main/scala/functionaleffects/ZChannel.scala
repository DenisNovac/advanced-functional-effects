package functionaleffects

import zio.Chunk

object Channels {

  // how channel is realted to other stream primitives?
  // internally all stream types are channel
  trait ZChannel[-Env, -InErr, -InElem, -InDone, +OutError, +OutElem, +OutDone]

  // stream is basically a source
  type ZStream[-R, +E, +A] = ZChannel[R, Any, Any, Any, E, Chunk[A], Any]

  // z = done value
  // out elem for sink is leftovers
  type ZSink[-R, +E, -In, +L, +Z]       = ZChannel[R, Nothing, Chunk[In], Any, E, Chunk[L], Z]
  type ZPipeline[-Env, +Err, -In, +Out] = ZChannel[Env, Nothing, Chunk[Int], Any, Err, Chunk[Out], Any]

  // done value = Nothing, infinite pipeline
  type InfinitePipeline[-Env, +Err, -In, +Out] = ZChannel[Env, Nothing, Chunk[Int], Any, Err, Chunk[Out], Nothing]

  // zio might be done like one-element stream with only "done" element
  // not acually implemented (dependencies, perfomance, complexity)
  type ZIO[-R, +E, +A] = ZChannel[R, Any, Any, Any, E, Nothing, A]

}
