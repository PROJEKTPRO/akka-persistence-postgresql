package akka.persistence.pg.journal

import java.sql.BatchUpdateException

import akka.actor.ActorLogging
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.pg.{PgActorConfig, PgExtension}
import akka.persistence.pg.event.{EventStore, EventTagger, JsonEncoder, StoredEvent}
import akka.persistence.{PersistentConfirmation, PersistentId, PersistentRepr}
import akka.serialization.{Serialization, SerializationExtension}

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Failure
import scala.util.control.NonFatal

class PgAsyncWriteJournal extends AsyncWriteJournal with ActorLogging with PgActorConfig with JournalStore {

  implicit val executionContext = context.system.dispatcher

  override val serialization: Serialization = SerializationExtension(context.system)
  override val pgExtension: PgExtension = PgExtension(context.system)
  override val pluginConfig = pgExtension.pluginConfig
  override val eventEncoder: JsonEncoder = pluginConfig.eventStoreConfig.eventEncoder
  override val eventTagger: EventTagger = pluginConfig.eventStoreConfig.eventTagger
  override val partitioner: Partitioner = pluginConfig.journalPartitioner

  val eventStore: Option[EventStore] = pluginConfig.eventStore

  import akka.persistence.pg.PgPostgresDriver.api._

  override def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]): Future[Unit] = {
    log.debug(s"asyncWriteMessages of ${messages.size} messages")
    val entries = toJournalEntries(messages)
    val storeActions: Seq[DBIO[_]] = Seq(journals ++= entries.map(_.entry))

    val actions: Seq[DBIO[_]] = eventStore match {
      case None        => storeActions
      case Some(store) => storeActions ++ store.postStoreActions(entries
          .filter { _.entry.json.isDefined }
          .map { entryWithEvent: JournalEntryWithEvent => StoredEvent(entryWithEvent.entry.persistenceId, entryWithEvent.event) }
        )
    }

    val r = db.run {
      DBIO.seq(actions:_*).transactionally
    }
    r.onFailure {
      case t: BatchUpdateException => log.error(t.getNextException, "problem storing events")
      case NonFatal (t) => log.error(t, "problem storing events")
    }
    r
  }

  @deprecated("asyncWriteConfirmations will be removed, since Channels will be removed.", since = "2.3.4")
  override def asyncWriteConfirmations(confirmations: immutable.Seq[PersistentConfirmation]): Future[Unit] = {
    Future.failed(new RuntimeException("unsupported"))
  }

  @deprecated("asyncDeleteMessages will be removed.", since = "2.3.4")
  override def asyncDeleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean): Future[Unit] = {
    Future.failed(new RuntimeException("unsupported"))
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    log.debug(s"Async read for highest sequence number for processorId: [$persistenceId] (hint, seek from  nr: [$fromSequenceNr])")
    db.run {
      journals
        .filter(_.persistenceId === persistenceId)
        .filter(byPartitionKey(persistenceId))
        .map((table: JournalTable) => table.sequenceNr)
        .max
        .result
    } map {
      _.getOrElse(0)
    }
  }

  private[this] def byPartitionKey(persistenceId: String): (JournalTable) => Rep[Option[Boolean]] = {
    j =>
      val partitionKey = partitioner.partitionKey(persistenceId)
      j.partitionKey.isEmpty && partitionKey.isEmpty || j.partitionKey === partitionKey
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)
                                  (replayCallback: (PersistentRepr) => Unit): Future[Unit] = {
    log.debug(s"Async replay for processorId [$persistenceId], from sequenceNr: [$fromSequenceNr], to sequenceNr: [$toSequenceNr] with max records: [$max]")
    db.run {
      journals
        .filter(_.persistenceId === persistenceId)
        .filter(_.sequenceNr >= fromSequenceNr)
        .filter(_.sequenceNr <= toSequenceNr)
        .filter(byPartitionKey(persistenceId))
        .sortBy(_.sequenceNr)
        .take(max)
        .result
    } map {
      _.map(toPersistentRepr).foreach(replayCallback)
    }
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Future[Unit] = {
    val selectedEntries = journals
      .filter(_.persistenceId === persistenceId)
      .filter(_.sequenceNr <= toSequenceNr)
      .filter(byPartitionKey(persistenceId))

    val action = if (permanent) {
        selectedEntries.delete
    } else {
        selectedEntries.map(_.deleted).update(true)
    }
    db.run(action).map(_ => ())
  }

}