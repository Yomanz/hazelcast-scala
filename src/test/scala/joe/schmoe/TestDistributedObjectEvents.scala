package joe.schmoe

import org.scalatest._

import com.hazelcast.Scala._
import scala.concurrent.duration._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import com.hazelcast.core.IMap
import com.hazelcast.map.impl.MapService
import com.hazelcast.core.HazelcastInstance
import scala.concurrent.ExecutionContext
import java.util.UUID

object TestDistributedObjectEvents extends ClusterSetup {
  override def clusterSize = 1
  def init = ()
  def destroy = ()
}

class TestDistributedObjectEvents extends FunSuite with BeforeAndAfterAll {
  import TestDistributedObjectEvents._

  override def beforeAll() = beforeClass()
  override def afterAll() = afterClass()

  test("any") {
    any(client)
    any(hzs(0))
    any(client, ExecutionContext.global)
    any(hzs(0), ExecutionContext.global)
  }
  private def any(hz: HazelcastInstance, ec: ExecutionContext = null): Unit = {
    val MapName = UUID.randomUUID.toString
    val expected = 2 // 1 created, 1 destroyed
    val counter = new AtomicInteger
    val cdl = new CountDownLatch(expected)
    val reg = hz.onDistributedObjectEvent(runOn = ec) {
      case evt @ DistributedObjectCreated(MapName, _) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
      case evt @ DistributedObjectDestroyed(MapName, _) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
    }
    try {
      val anyMap = hz.getMap[Int, Int](MapName)
      anyMap.put(1, 1)
      anyMap.destroy()
      assert(cdl.await(60, SECONDS))
      assert(counter.get == expected)
    } finally reg.cancel()
  }

  test("typed") {
    typed(client)
    typed(hzs(0))
    typed(client, ExecutionContext.global)
    typed(hzs(0), ExecutionContext.global)
  }
  private def typed(hz: HazelcastInstance, ec: ExecutionContext = null): Unit = {
    val expected = 4 // 2 IMap created, 2 IMap destroyed
    val counter = new AtomicInteger
    val cdl = new CountDownLatch(expected)
    val reg = hz.onDistributedObjectEvent(runOn = ec) {
      case evt @ DistributedObjectCreated(_, _: IMap[_, _]) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
      case evt @ DistributedObjectDestroyed(_, MapService.SERVICE_NAME) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
    }
    try {
      val fooMap = hz.getMap[Int, Int]("foo")
      fooMap.put(1, 1)
      val fooQueue = hz.getQueue[Int]("foo")
      fooQueue.offer(1)
      val barMap = hz.getMap[Int, Int]("bar")
      barMap.put(1, 1)
      val barbeQueue = hz.getQueue[Int]("bar")
      barbeQueue.offer(1)
      fooMap.destroy()
      fooQueue.destroy()
      barMap.destroy()
      barbeQueue.destroy()
      assert(cdl.await(60, SECONDS))
      assert(counter.get == expected)
    } finally reg.cancel()
  }

  test("named") {
    named(client)
    named(hzs(0))
    named(client, ExecutionContext.global)
    named(hzs(0), ExecutionContext.global)
  }
  private def named(hz: HazelcastInstance, ec: ExecutionContext = null): Unit = {
    val expected = 4 // 2 "foo" created, 2 "foo" destroyed
    val counter = new AtomicInteger
    val cdl = new CountDownLatch(expected)
    val reg = hz.onDistributedObjectEvent(runOn = ec) {
      case evt @ DistributedObjectCreated("foo", _) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
      case evt @ DistributedObjectDestroyed("foo", _) =>
        assert(counter.incrementAndGet <= expected, evt)
        cdl.countDown()
    }
    try {
      val fooMap = hz.getMap[Int, Int]("foo")
      fooMap.put(1, 1)
      val fooQueue = hz.getQueue[Int]("foo")
      fooQueue.offer(1)
      val barMap = hz.getMap[Int, Int]("bar")
      barMap.put(1, 1)
      val barbeQueue = hz.getQueue[Int]("bar")
      barbeQueue.offer(1)
      fooMap.destroy()
      fooQueue.destroy()
      barMap.destroy()
      barbeQueue.destroy()
      assert(cdl.await(60, SECONDS))
      assert(counter.get == expected)
    } finally reg.cancel()
  }

}
