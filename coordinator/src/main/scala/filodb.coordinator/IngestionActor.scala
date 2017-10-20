package filodb.coordinator

import scala.collection.mutable.HashMap
import scala.util.control.NonFatal
import scala.util.Try

import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import monix.execution.{Cancelable, Scheduler}
import monix.eval.Task

import filodb.core.memstore._
import filodb.core.metadata.Dataset
import filodb.core.DatasetRef

object IngestionActor {

  final case class IngestRows(ackTo: ActorRef, shard: Int, records: Seq[IngestRecord])

  case object GetStatus

  final case class IngestionStatus(rowsIngested: Long)

  def props(dataset: Dataset,
            memStore: MemStore,
            source: NodeClusterActor.IngestionSource,
            shardActor: ActorRef)(implicit sched: Scheduler): Props =
    Props(classOf[IngestionActor], dataset, memStore, source, shardActor, sched)
}

/**
  * Simply a wrapper for ingesting new records into a MemStore
  * Also starts up an IngestionStream streaming directly into MemStore.
  *
  * ERROR HANDLING: currently any error in ingestion stream or memstore ingestion wll stop the ingestion
  *
  * @param sched a Scheduler for running ingestion stream Observables
  */
private[filodb] final class IngestionActor(dataset: Dataset,
                                           memStore: MemStore,
                                           source: NodeClusterActor.IngestionSource,
                                           shardActor: ActorRef)
                                          (implicit sched: Scheduler) extends BaseActor {

  import IngestionActor._

  final val streamSubscriptions = new HashMap[Int, Cancelable]
  final val streams = new HashMap[Int, IngestionStream]

  // TODO: add and remove per-shard ingestion sources?
  // For now just start it up one time and kill the actor if it fails
  val ctor = Class.forName(source.streamFactoryClass).getConstructors.head
  val streamFactory = ctor.newInstance().asInstanceOf[IngestionStreamFactory]
  logger.info(s"Using stream factory $streamFactory with config ${source.config}")

  override def postStop(): Unit = {
    super.postStop() // <- logs shutting down
    logger.info("Cancelling all streams and calling teardown")
    streamSubscriptions.keys.foreach(stop(dataset.ref, _, ActorRef.noSender))
  }

  /** All [[ShardCommand]] tasks are only started if the dataset
    * and shard are valid for this ingester.
    */
  def receive: Receive = LoggingReceive {
    case e: StartShardIngestion        => start(e, sender())
    case e: IngestRows                 => ingest(e)
    case GetStatus                     => status(sender())
    case StopShardIngestion(ds, shard) => stop(ds, shard, sender())
  }

  /** Guards that only this dataset's commands are acted upon.
    * Handles initial memstore setup of dataset to shard.
    */
  private def start(e: StartShardIngestion, origin: ActorRef): Unit =
    if (invalid(e.ref)) handleInvalid(e, Some(origin)) else {
      // TODO(velvia): user-configurable error handling?  Should we stop?  Should we restart?

      try memStore.setup(dataset, e.shard) catch {
        case ex@DatasetAlreadySetup(ds) =>
          logger.warn(s"Dataset $ds already setup", ex)
        case ShardAlreadySetup =>
          logger.warn(s"Shard already setup")
      }

      // TODO(velvia): user-configurable error handling?  Should we stop?  Should we restart?
      create(e, origin) map { ingestionStream =>
        val stream = ingestionStream.get
        shardActor ! IngestionStarted(dataset.ref, e.shard, context.parent)

        stream
          .doOnCompleteEval(Task.eval(shardActor ! IngestionStopped(dataset.ref, e.shard)))
          .onErrorRecover { case NonFatal(ex) => handleError(dataset.ref, e.shard, ex) }

        streamSubscriptions(e.shard) = memStore.ingestStream(dataset.ref, e.shard, stream) {
          ex => handleError(dataset.ref, e.shard, ex)
        }
      } recover { case NonFatal(t) =>
        handleError(e.ref, e.shard, t)
      }
    }

  /** [[filodb.coordinator.IngestionStreamFactory.create]] can raise IllegalArgumentException
    * if the shard is not 0. This will notify versus throw so the sender can handle the
    * problem, which is internal.
    */
  private def create(e: StartShardIngestion, origin: ActorRef): Try[IngestionStream] =
    Try {
      val ingestStream = streamFactory.create(source.config, dataset, e.shard)
      streams(e.shard) = ingestStream
      logger.info(s"Ingestion stream $ingestStream set up for shard ${e.shard}")
      ingestStream
    }

  private def ingest(e: IngestRows): Unit = {
    memStore.ingest(dataset.ref, e.shard, e.records)
    if (e.records.nonEmpty) {
      e.ackTo ! IngestionCommands.Ack(e.records.last.offset)
    }
  }

  private def status(origin: ActorRef): Unit =
    origin ! IngestionStatus(memStore.numRowsIngested(dataset.ref))

  /** Guards that only this dataset's commands are acted upon. */
  private def stop(ds: DatasetRef, shard: Int, origin: ActorRef): Unit =
    if (invalid(ds)) handleInvalid(StopShardIngestion(ds, shard), Some(origin)) else {
      streamSubscriptions.remove(shard).foreach(_.cancel)
      streams.remove(shard).foreach(_.teardown())
      shardActor ! IngestionStopped(dataset.ref, shard)

      // TODO: release memory for shard in MemStore
      logger.info(s"Stopped streaming ingestion for shard $shard and released resources")
  }

  private def invalid(ref: DatasetRef): Boolean = ref != dataset.ref

  private def handleError(ref: DatasetRef, shard: Int, err: Throwable): Unit = {
    shardActor ! IngestionError(ref, shard, err)
    logger.error("Exception thrown during ingestion stream", err)
  }

  private def handleInvalid(command: ShardCommand, origin: Option[ActorRef]): Unit = {
    logger.error(s"$command is invalid for this ingester '${dataset.ref}'.")
    origin foreach(_ ! InvalidIngestionCommand(command.ref, command.shard))
  }

  private def recover(): Unit = {
    // TODO: start recovery, then.. could also be in Actor.preRestart()
    // statusActor ! RecoveryStarted(dataset.ref, shard, context.parent)
  }
}