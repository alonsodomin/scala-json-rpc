package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import io.circe.Json
import net.flatmap.jsonrpc.ExampleInterfaces.OverloadError
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.{ExecutionContext, Future}

object ExampleInterfaces {
  trait Simple {
    def f(x: Int): Future[String]
    def g(x: String)

    @JsonRPCMethod("blub") def h(x: String)

    def concrete(x: String)
                (implicit ec: ExecutionContext) =
      f(x.toInt).map(_.toInt)

    @JsonRPCNamespace("nested/")
    def nested: Nested

    def optional(f: String, y: Option[Int]): Future[String]
  }

  trait Nested {
    def foo: Future[Int]
  }

  trait OverloadError {
    def f(x: Int): Future[String]
    def f(x: String): Future[String]
  }

  trait GenericsError {
    def f[T](x: T): Future[String]
  }
}


class RemoteInterfaceSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  "automatic derivation" should "fail, when an overloaded method is present" in {
    "Remote[ExampleInterfaces.OverloadError](Id.standard)" shouldNot compile
  }

  it should "fail, when a method is polymorphic" in {
    "Remote[ExampleInterfaces.GenericsError](Id.standard)" shouldNot typeCheck
  }

  it should "succeed if none of the above applies" in {
    "Remote[ExampleInterfaces.Simple](Id.standard)" should compile
  }

  "a derived remote interface" should "produce request messages for methods " +
    "with return type Future[T]" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.empty
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    interface.f(42)
    interface.f(17)
    interface.close()
    whenReady(f) { x =>
      x should have length 2
      x shouldBe Seq(
        Request(Id.Long(0),"f",NamedParameters(Map("x" -> Json.fromInt(42)))),
        Request(Id.Long(1),"f",NamedParameters(Map("x" -> Json.fromInt(17))))
      )
    }
  }

  it should "produce notification messages for methods " +
    "with return Unit" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.empty
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    interface.g("foo")
    interface.g("bar")
    interface.close()
    whenReady(f) { x =>
      x should have length 2
      x shouldBe Seq(
        Notification("g",NamedParameters(Map("x" -> Json.fromString("foo")))),
        Notification("g",NamedParameters(Map("x" -> Json.fromString("bar"))))
      )
    }
  }

  it should "respect custom name annotations" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.empty
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    interface.h("blubber")
    interface.close()
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Notification("blub",NamedParameters(Map("x" -> Json.fromString
        ("blubber"))))
      )
    }
  }

  it should "implement nested interfaces" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.empty
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    interface.nested.foo
    interface.close()

    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Request(Id.Long(0),"nested/foo",NoParameters)
      )
    }
  }

  it should "complete the futures when responding to a request" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.maybe[Response]
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    val x = interface.f(42) // Id.Long(0)
    val y = interface.f(17) // Id.Long(1)
    val z = interface.f(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(Response.Success(Id.Long(1),Json.fromString("blub"))))
    interface.close()

    whenReady(y) { r => r shouldEqual "blub" }
  }

  it should "produce forward messages when concrete methods are called" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.maybe[Response]
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    val x = interface.concrete("42") // Id.Long(0)
    // respond to call "x"
    p.success(Some(Response.Success(Id.Long(0),Json.fromString("17"))))
    interface.close()

    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Request(Id.Long(0),"f",NamedParameters(Map("x" -> Json.fromInt(42))))
      )
    }
    whenReady(x) { r => r shouldEqual 17 }
  }

  it should "fail the futures when responding to a request with an error" in {
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)
    val source = Source.maybe[Response]
    val sink = Sink.seq[RequestMessage]
    val ((p,interface), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    val x = interface.f(42) // Id.Long(0)
    val y = interface.f(17) // Id.Long(1)
    val z = interface.f(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(Response.Failure(Id.Long(1), ResponseError(
      17,"fail!",None
    ))))

    interface.close()

    whenReady(y.failed) { r =>
      r shouldBe a[ResponseError]
      r.getMessage shouldEqual "fail!"
    }
  }
}
