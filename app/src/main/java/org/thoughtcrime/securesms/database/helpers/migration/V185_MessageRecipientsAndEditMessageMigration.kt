package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import androidx.core.content.contentValuesOf
import org.signal.core.util.SqlUtil
import org.signal.core.util.Stopwatch
import org.signal.core.util.logging.Log
import org.signal.core.util.readToList
import org.signal.core.util.readToSingleInt
import org.signal.core.util.readToSingleObject
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.thoughtcrime.securesms.database.KeyValueDatabase
import org.thoughtcrime.securesms.database.SQLiteDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.SecurePreferenceManager
import org.whispersystems.signalservice.api.push.ServiceId.ACI

/**
 * This is a combination of the edit message and message recipient migrations (would have been V185 and v186), but as they
 * both require recreating the message table, they are merged into one.
 *
 * Original V185:
 * Our current column setup for knowing is the the sender/receiver of a message is both confusing and non-optimal from a performance perspective.
 * This moves to a world where instead of tracking a single recipient, we track two: a sender and receiver.
 *
 * Original V186:
 * Changes needed for edit message. New foreign keys require recreating the table.
 *
 */
@Suppress("ClassName")
object V185_MessageRecipientsAndEditMessageMigration : SignalDatabaseMigration {

  private val TAG = Log.tag(V185_MessageRecipientsAndEditMessageMigration::class.java)

  private val outgoingClause = "(" + listOf(21, 23, 22, 24, 25, 26, 2, 11)
    .map { "type & ${0x1F} = $it" }
    .joinToString(separator = " OR ") + ")"

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    val stopwatch = Stopwatch("migration")

    var selfId: RecipientId? = getSelfId(db)

    if (selfId == null) {
      val outgoingMessageCount = db.rawQuery("SELECT COUNT(*) FROM message WHERE $outgoingClause").readToSingleInt()
      if (outgoingMessageCount == 0) {
        Log.i(TAG, "Could not find ourselves in the DB! Assuming this is an install that hasn't been registered yet.")
      } else {
        Log.w(TAG, "There's outgoing messages, but no self recipient! Attempting to repair.")

        val localAci: ACI? = getLocalAci(context)
        val localE164: String? = getLocalE164(context)

        if (localAci != null || localE164 != null) {
          Log.w(TAG, "Inserting a recipient for our local data.")
          val contentValues = contentValuesOf(
            "uuid" to localAci.toString(),
            "number" to localE164
          )

          val id = db.insert("recipient", null, contentValues)
          selfId = RecipientId.from(id)
        } else {
          Log.w(TAG, "No local recipient data at all! Have to bail.")
          return
        }
      }
    }

    stopwatch.split("get-self")

    val dependentItems: List<SqlItem> = getAllDependentItems(db, "message")

    dependentItems.forEach { item ->
      val sql = "DROP ${item.type} IF EXISTS ${item.name}"
      Log.d(TAG, "Executing: $sql")
      db.execSQL(sql)
    }

    stopwatch.split("drop-dependents")

    db.execSQL(
      """
        CREATE TABLE message_tmp (
          _id INTEGER PRIMARY KEY AUTOINCREMENT, 
          date_sent INTEGER NOT NULL, 
          date_received INTEGER NOT NULL,
          date_server INTEGER DEFAULT -1,
          thread_id INTEGER NOT NULL REFERENCES thread (_id) ON DELETE CASCADE,
          from_recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
          from_device_id INTEGER,
          to_recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
          type INTEGER NOT NULL,
          body TEXT,
          read INTEGER DEFAULT 0,
          ct_l TEXT,
          exp INTEGER,
          m_type INTEGER,
          m_size INTEGER,
          st INTEGER,
          tr_id TEXT,
          subscription_id INTEGER DEFAULT -1,
          receipt_timestamp INTEGER DEFAULT -1,
          delivery_receipt_count INTEGER DEFAULT 0,
          read_receipt_count INTEGER DEFAULT 0,
          viewed_receipt_count INTEGER DEFAULT 0,
          mismatched_identities TEXT DEFAULT NULL,
          network_failures TEXT DEFAULT NULL,
          expires_in INTEGER DEFAULT 0,
          expire_started INTEGER DEFAULT 0,
          notified INTEGER DEFAULT 0,
          quote_id INTEGER DEFAULT 0,
          quote_author INTEGER DEFAULT 0,
          quote_body TEXT DEFAULT NULL,
          quote_missing INTEGER DEFAULT 0,
          quote_mentions BLOB DEFAULT NULL,
          quote_type INTEGER DEFAULT 0,
          shared_contacts TEXT DEFAULT NULL,
          unidentified INTEGER DEFAULT 0,
          link_previews TEXT DEFAULT NULL,
          view_once INTEGER DEFAULT 0,
          reactions_unread INTEGER DEFAULT 0,
          reactions_last_seen INTEGER DEFAULT -1,
          remote_deleted INTEGER DEFAULT 0,
          mentions_self INTEGER DEFAULT 0,
          notified_timestamp INTEGER DEFAULT 0,
          server_guid TEXT DEFAULT NULL,
          message_ranges BLOB DEFAULT NULL,
          story_type INTEGER DEFAULT 0,
          parent_story_id INTEGER DEFAULT 0,
          export_state BLOB DEFAULT NULL,
          exported INTEGER DEFAULT 0,
          scheduled_date INTEGER DEFAULT -1,
          latest_revision_id INTEGER DEFAULT NULL REFERENCES message (_id) ON DELETE CASCADE,
          original_message_id INTEGER DEFAULT NULL REFERENCES message (_id) ON DELETE CASCADE,
          revision_number INTEGER DEFAULT 0
        )
      """
    )
    stopwatch.split("create-table")

    db.execSQL(
      """
        INSERT INTO message_tmp 
          SELECT
            _id, 
            date_sent, 
            date_received,
            date_server,
            thread_id,
            recipient_id,
            recipient_device_id,
            recipient_id,
            type,
            body,
            read,
            ct_l,
            exp,
            m_type,
            m_size,
            st,
            tr_id,
            subscription_id,
            receipt_timestamp,
            delivery_receipt_count,
            read_receipt_count,
            viewed_receipt_count,
            mismatched_identities,
            network_failures,
            expires_in,
            expire_started,
            notified,
            quote_id,
            quote_author,
            quote_body,
            quote_missing,
            quote_mentions,
            quote_type,
            shared_contacts,
            unidentified,
            link_previews,
            view_once,
            reactions_unread,
            reactions_last_seen,
            remote_deleted,
            mentions_self,
            notified_timestamp,
            server_guid,
            message_ranges,
            story_type,
            parent_story_id,
            export_state,
            exported,
            scheduled_date,
            NULL AS latest_revision_id,
            NULL AS original_message_id,
            0 as revision_number
          FROM message
      """
    )
    stopwatch.split("copy-data")

    // Previously, the recipient_id on an outgoing message represented who it was going to (an individual or group).
    // So if a message is outgoing, we'll set to = from, then from = self
    if (selfId != null) {
      db.execSQL(
        """
        UPDATE message_tmp
        SET 
          to_recipient_id = from_recipient_id,
          from_recipient_id = ${selfId.toLong()},
          from_device_id = 1
        WHERE $outgoingClause 
        """
      )
    }
    stopwatch.split("update-data")

    db.execSQL("DROP TABLE message")
    stopwatch.split("drop-old-table")

    db.execSQL("ALTER TABLE message_tmp RENAME TO message")
    stopwatch.split("rename-table")

    dependentItems.forEach { item ->
      val sql = when (item.name) {
        "mms_thread_story_parent_story_scheduled_date_index" -> "CREATE INDEX message_thread_story_parent_story_scheduled_date_latest_revision_id_index ON message (thread_id, date_received, story_type, parent_story_id, scheduled_date, latest_revision_id)"
        "mms_quote_id_quote_author_scheduled_date_index" -> "CREATE INDEX message_quote_id_quote_author_scheduled_date_latest_revision_id_index ON message (quote_id, quote_author, scheduled_date, latest_revision_id)"
        "mms_date_sent_index" -> "CREATE INDEX message_date_sent_from_to_thread_index ON message (date_sent, from_recipient_id, to_recipient_id, thread_id)"
        else -> item.createStatement.replace(Regex.fromLiteral("CREATE INDEX mms_"), "CREATE INDEX message_")
      }
      Log.d(TAG, "Executing: $sql")
      db.execSQL(sql)
    }
    stopwatch.split("recreate-dependents")

    val foreignKeyViolations: List<SqlUtil.ForeignKeyViolation> = SqlUtil.getForeignKeyViolations(db, "message")
    if (foreignKeyViolations.isNotEmpty()) {
      Log.w(TAG, "Foreign key violations!\n${foreignKeyViolations.joinToString(separator = "\n")}")
      throw IllegalStateException("Foreign key violations!")
    }
    stopwatch.split("fk-check")

    stopwatch.stop(TAG)
  }

  private fun getSelfId(db: SQLiteDatabase): RecipientId? {
    val idByAci: RecipientId? = getLocalAci(AppDependencies.application)?.let { aci ->
      db.rawQuery("SELECT _id FROM recipient WHERE uuid = ?", SqlUtil.buildArgs(aci))
        .readToSingleObject { RecipientId.from(it.requireLong("_id")) }
    }

    if (idByAci != null) {
      return idByAci
    }

    Log.w(TAG, "Failed to find by ACI! Will try by E164.")

    val idByE164: RecipientId? = getLocalE164(AppDependencies.application)?.let { e164 ->
      db.rawQuery("SELECT _id FROM recipient WHERE phone = ?", SqlUtil.buildArgs(e164))
        .readToSingleObject { RecipientId.from(it.requireLong("_id")) }
    }

    if (idByE164 == null) {
      Log.w(TAG, "Also failed to find by E164!")
    }

    return idByE164
  }

  private fun getLocalAci(context: Application): ACI? {
    if (KeyValueDatabase.exists(context)) {
      val keyValueDatabase = KeyValueDatabase.getInstance(context).readableDatabase
      keyValueDatabase.query("key_value", arrayOf("value"), "key IN (?, ?)", SqlUtil.buildArgs("account.aci", "account.1.aci"), null, null, null).use { cursor ->
        return if (cursor.moveToFirst()) {
          ACI.parseOrNull(cursor.requireString("value"))
        } else {
          Log.w(TAG, "ACI not present in KV database!")
          null
        }
      }
    } else {
      Log.w(TAG, "Pre-KV database -- searching for ACI in shared prefs.")
      return ACI.parseOrNull(SecurePreferenceManager.getSecurePreferences(context).getString("pref_local_uuid", null))
    }
  }

  private fun getLocalE164(context: Application): String? {
    if (KeyValueDatabase.exists(context)) {
      val keyValueDatabase = KeyValueDatabase.getInstance(context).readableDatabase
      keyValueDatabase.query("key_value", arrayOf("value"), "key IN (?, ?)", SqlUtil.buildArgs("account.e164", "account.1.e164"), null, null, null).use { cursor ->
        return if (cursor.moveToFirst()) {
          cursor.requireString("value")
        } else {
          Log.w(TAG, "E164 not present in KV database!")
          null
        }
      }
    } else {
      Log.w(TAG, "Pre-KV database -- searching for E164 in shared prefs.")
      return SecurePreferenceManager.getSecurePreferences(context).getString("pref_local_number", null)
    }
  }

  private fun getAllDependentItems(db: SQLiteDatabase, tableName: String): List<SqlItem> {
    return db.rawQuery("SELECT type, name, sql FROM sqlite_schema WHERE tbl_name='$tableName' AND type != 'table'").readToList { cursor ->
      SqlItem(
        type = cursor.requireNonNullString("type"),
        name = cursor.requireNonNullString("name"),
        createStatement = cursor.requireNonNullString("sql")
      )
    }
  }

  data class SqlItem(
    val type: String,
    val name: String,
    val createStatement: String
  )
}
