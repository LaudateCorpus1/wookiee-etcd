package com.oracle.infy.grpc

import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.{ConcurrentEffect, ContextShift, IO}
import com.oracle.infy.grpc.common.ConstableCommon
import com.oracle.infy.grpc.contract.ListenerContract
import com.oracle.infy.grpc.impl.{Fs2CloseableImpl, WookieeGrpcHostListener, ZookeeperHostnameService}
import com.oracle.infy.grpc.json.HostSerde
import com.oracle.infy.grpc.model.Host
import com.oracle.infy.grpc.tests.GrpcListenerTest
import fs2.Stream
import fs2.concurrent.Queue
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.apache.curator.framework.recipes.cache.CuratorCache
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import utils.implicits._

import scala.concurrent.ExecutionContext

object IntegrationConstable extends ConstableCommon {

  def curatorFactory(connStr: String): CuratorFramework = {
    CuratorFrameworkFactory
      .builder()
      .connectString(connStr)
      .retryPolicy(new ExponentialBackoffRetry(1000, 3000))
      .build()
  }

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    implicit val concurrent: ConcurrentEffect[IO] = IO.ioConcurrentEffect

    val zkFake = new TestingServer()
    val connStr = zkFake.getConnectString
    val curator = curatorFactory(connStr)
    curator.start()

    val discoveryPath = "/example"
    curator.create.orSetData().forPath(discoveryPath)
    curator.close()

    def pushMessagesFuncAndListenerFactory(
        callback: Set[Host] => IO[Unit]
    ): IO[(Set[Host] => IO[Unit], () => IO[Unit], ListenerContract[IO, Stream])] = {
      for {
        queue <- Queue.unbounded[IO, Set[Host]]
        killSwitch <- Deferred[IO, Either[Throwable, Unit]]

        logger <- Slf4jLogger.create[IO]
        hostConsumerCuratorRef <- Ref.of[IO, CuratorFramework](curatorFactory(connStr))
        hostProducerCurator <- IO {
          val curator = curatorFactory(connStr)
          curator.start()
          curator
        }
        semaphore <- Semaphore(1)
        cache <- Ref.of[IO, Option[CuratorCache]](None)

      } yield {

        val pushMessagesFunc = { hosts: Set[Host] =>
          IO {
            hosts.foreach { host =>
              val nodePath = s"$discoveryPath/${host.address}"
              hostProducerCurator.create().orSetData().forPath(nodePath, HostSerde.serialize(host))
            }
          }
        }

        val listener: ListenerContract[IO, Stream] =
          new WookieeGrpcHostListener(
            callback,
            new ZookeeperHostnameService(
              hostConsumerCuratorRef,
              cache,
              semaphore,
              Fs2CloseableImpl(queue.dequeue, killSwitch),
              queue.enqueue1
            )(concurrent, logger),
            discoveryPath = discoveryPath
          )

        val cleanup: () => IO[Unit] = () => {
          IO {
            hostProducerCurator.getChildren.forPath(discoveryPath).asScala.foreach { child =>
              hostProducerCurator.delete().guaranteed().forPath(s"$discoveryPath/$child")
            }
            hostProducerCurator.close()
            ()
          }
        }
        (pushMessagesFunc, cleanup, listener)
      }
    }

    val grpcTests = GrpcListenerTest.tests(pushMessagesFuncAndListenerFactory)

    runTestsAsync(List(grpcTests -> "GrpcTest"))
    zkFake.stop()

    ()
  }
}
