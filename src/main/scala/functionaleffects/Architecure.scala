package functionaleffects

import zio._
import java.io.IOException

/** In ZIO we want to use onion architecture (Service pattern)
  *
  * Idea: define higher level services in terms of lower level services
  *
  * For each of services we create layer that implements it
  */

sealed trait Github {
  def addComment(repo: String, issue: Int, comment: String): UIO[Unit]
}

object Github {
  // accessor methods to not write _ <- ZIO.service[Github] each time
  def addComment(repo: String, issue: Int, comment: String) =
    ZIO.serviceWithZIO[Github](_.addComment(repo, issue, comment))

  final case class GithubLive(http: Http) extends Github {
    override def addComment(repo: String, issue: Int, comment: String): UIO[Unit] = ???
  }

  // layer construction is effect - and we could call effects while creating it
  // good for effectful startup (migrations, etc)
  val live: ZLayer[Http, Nothing, Github] =
    ZLayer.fromFunction(GithubLive(_))
}

trait Http {
  def get(url: String): IO[IOException, String]
  def post(url: String, body: String): IO[IOException, String]
}

object Http {
  final case class HttpLive() extends Http {
    override def get(url: String): IO[IOException, String]                = ???
    override def post(url: String, body: String): IO[IOException, String] = ???
  }

  val live: ZLayer[Any, Nothing, Http] =
    ZLayer.succeed(HttpLive())
}

object Main extends ZIOAppDefault {

  val application = for {
    _ <- Github.addComment("zio/zio", 1, "Hello world")
  } yield ()

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] =
    application.provide(
      Github.live, // this requires HTTP, but other implementations might not
      Http.live    // so we added this
    )

}
