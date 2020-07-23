package com.oracle.infy.grpc.common

import cats.data.EitherT
import cats.effect.{ContextShift, IO, Timer}
import com.oracle.infy.grpc.errors.Errors.WookieeGrpcError
import org.scalacheck.Prop
import utest.framework.{Formatter, HTree, Result}
import utest.ufansi.Str
import utest.{TestRunner, Tests, ufansi}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait ConstableCommon {

  implicit def eitherTListenerErrorToProp: EitherT[IO, WookieeGrpcError, Boolean] => Prop = { e =>
    val result = e
      .value
      .unsafeRunSync()
      .left
      .map(err => {
        println(err)
        false
      })
      .merge
    Prop(result)
  }

  private val testFormatter =
    new Formatter {

      override def formatIcon(success: Boolean): Str = {
        formatResultColor(success)(
          if (success) "✅💯✅" else "\uD83E\uDD26\u200D\uD83E\uDD26\u200D\uD83E\uDD26\u200D️"
        )
      }
    }

  def runTests(
      tests: List[(Tests, String)]
  ): List[HTree[String, Result]] = {
    tests.map {
      case (test, label) =>
        TestRunner
          .runAndPrint(
            test,
            label,
            formatter = testFormatter
          )
    }
  }

  def runTestsAsync(
      tests: List[(Tests, String)]
  )(implicit ec: ExecutionContext, cs: ContextShift[IO]): List[HTree[String, Result]] = {
    IO.fromFuture {
        IO {
          Future
            .sequence(
              tests.map {
                case (test, label) =>
                  TestRunner
                    .runAndPrintAsync(
                      test,
                      label,
                      formatter = testFormatter
                    )
              }
            )
            .map { results =>
              formatResults(results)
              results
            }

        }
      }
      .unsafeRunSync()
  }

  private def formatResults(results: Seq[HTree[String, Result]]): Unit = {

    results.flatMap(tree => tree.leaves.map(_.value.isFailure)).find(identity).foreach { _ =>
      println()
      println(ufansi.Color.Red("T E S T S  F A I L E D"))
      println("\"Laws change, depending on who's making them, but justice is justice.\" - Constable Odo")
    }
  }

  implicit def sleep(implicit timer: Timer[IO]): FiniteDuration => IO[Unit] = { duration: FiniteDuration =>
    IO.sleep(duration)
  }
}
