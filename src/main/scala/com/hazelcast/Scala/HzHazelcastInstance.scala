package com.hazelcast.Scala

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import com.hazelcast.core._
import com.hazelcast.core.LifecycleEvent.LifecycleState
import com.hazelcast.partition.PartitionLostEvent
import com.hazelcast.transaction.TransactionOptions
import com.hazelcast.transaction.TransactionOptions.TransactionType
import com.hazelcast.transaction.TransactionalTask
import com.hazelcast.transaction.TransactionalTaskContext

private object HzHazelcastInstance {
  private[this] val DefaultTxnOpts = TransactionOptions.getDefault
  private val DefaultTxnType = DefaultTxnOpts.getTransactionType match {
    case TransactionType.ONE_PHASE | TransactionType.LOCAL => OnePhase
    case TransactionType.TWO_PHASE => TwoPhase(DefaultTxnOpts.getDurability)
  }
  private val DefaultTxnTimeout = FiniteDuration(TransactionOptions.getDefault.getTimeoutMillis, TimeUnit.MILLISECONDS)
}

final class HzHazelcastInstance(hz: HazelcastInstance) extends MemberEventSubscription {
  import HzHazelcastInstance._

  type ESR = ListenerRegistration

  private[Scala] def groupByPartition[K](keys: collection.Set[K]): Map[Partition, collection.Set[K]] = {
    val ps = hz.getPartitionService
    keys.groupBy(ps.getPartition)
  }
  private[Scala] def groupByMember[K](keys: collection.Set[K]): Map[Member, collection.Set[K]] = {
    val ps = hz.getPartitionService
    keys.groupBy(ps.getPartition(_).getOwner)
  }

  private[Scala] def queryPool(): IExecutorService = hz.getExecutorService("hz:query")

  def onDistributedObjectEvent(runOn: ExecutionContext = null)(listener: PartialFunction[DistributedObjectChange, Unit]): ESR = {
    val regId = hz addDistributedObjectListener asDistributedObjectListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel() = hz removeDistributedObjectListener regId
    }
  }

  def onLifecycleStateChange(runOn: ExecutionContext = null)(listener: PartialFunction[LifecycleState, Unit]): ESR = {
    val service = hz.getLifecycleService
    val regId = service addLifecycleListener asLifecycleListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel() = service removeLifecycleListener regId
    }
  }

  def onPartitionLost(runOn: ExecutionContext = null)(listener: PartitionLostEvent => Unit): ESR = {
    val service = hz.getPartitionService
    val regId = service addPartitionLostListener asPartitionLostListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel(): Unit = service removePartitionLostListener regId
    }
  }
  def onMigration(runOn: ExecutionContext = null)(listener: PartialFunction[MigrationEvent, Unit]): ESR = {
    val service = hz.getPartitionService
    val regId = service addMigrationListener asMigrationListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel(): Unit = service removeMigrationListener regId
    }
  }
  def onClient(runOn: ExecutionContext = null)(listener: PartialFunction[ClientEvent, Unit]): ESR = {
    val service = hz.getClientService
    val regId = service addClientListener asClientListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel(): Unit = service removeClientListener regId
    }
  }
  type MER = (ESR, Future[InitialMembershipEvent])
  def onMemberChange(runOn: ExecutionContext = null)(listener: PartialFunction[MemberEvent, Unit]): MER = {
    val cluster = hz.getCluster
    val (future, mbrListener) = asMembershipListener(listener, Option(runOn))
    val regId = cluster addMembershipListener mbrListener
    new ListenerRegistration {
      def cancel(): Unit = cluster removeMembershipListener regId
    } -> future
  }

  /**
    * Execute transaction.
    * @param durability Number of backups
    * @param transactionType Type of transaction
    * @param timeout Transaction timeout
    */
  def transaction[T](
    txnType: TxnType = DefaultTxnType,
    timeout: FiniteDuration = DefaultTxnTimeout)(thunk: TransactionalTaskContext => T): T = {
    val opts = new TransactionOptions().setTimeout(timeout.length, timeout.unit)
    txnType match {
      case OnePhase =>
        opts.setTransactionType(TransactionType.ONE_PHASE)
      case TwoPhase(durability) =>
        opts.setTransactionType(TransactionType.TWO_PHASE).setDurability(durability)
    }
    transaction(opts)(thunk)
  }
  def transaction[T](opts: TransactionOptions)(thunk: TransactionalTaskContext => T): T = {
    val task = new TransactionalTask[T] {
      def execute(ctx: TransactionalTaskContext) = thunk(ctx)
    }
    if (opts == null) {
      hz.executeTransaction(task)
    } else {
      hz.executeTransaction(opts, task)
    }
  }

  def isClient: Boolean = {
    val cluster = hz.getCluster
    try {
      cluster.getLocalMember == null
    } catch {
      case _: UnsupportedOperationException => true
    }
  }

  def userCtx: UserContext = new UserContext(hz.getUserContext)
}
