/*
 * Copyright (C) 2018 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database;

import android.content.Context;
import androidx.annotation.NonNull;

import net.sqlcipher.database.SQLiteDatabase;

import org.thoughtcrime.securesms.contacts.ContactsDatabase;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.crypto.DatabaseSecret;
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

public class DatabaseFactory {

  private static final Object lock = new Object();

  private static DatabaseFactory instance;

  private final SQLCipherOpenHelper     databaseHelper;
  private final SmsDatabase             sms;
  private final MmsDatabase             mms;
  private final AttachmentDatabase      attachments;
  private final MediaDatabase           media;
  private final ThreadDatabase          thread;
  private final MmsSmsDatabase          mmsSmsDatabase;
  private final IdentityDatabase        identityDatabase;
  private final DraftDatabase           draftDatabase;
  private final PushDatabase            pushDatabase;
  private final GroupDatabase           groupDatabase;
  private final RecipientDatabase       recipientDatabase;
  private final ContactsDatabase        contactsDatabase;
  private final GroupReceiptDatabase    groupReceiptDatabase;
  private final OneTimePreKeyDatabase   preKeyDatabase;
  private final SignedPreKeyDatabase    signedPreKeyDatabase;
  private final SessionDatabase         sessionDatabase;
  private final SearchDatabase          searchDatabase;
  private final JobDatabase             jobDatabase;
  private final StickerDatabase         stickerDatabase;
  private final StorageKeyDatabase      storageKeyDatabase;
  private final KeyValueDatabase        keyValueDatabase;
  private final MegaphoneDatabase       megaphoneDatabase;
  private final RemappedRecordsDatabase remappedRecordsDatabase;
  private final MentionDatabase         mentionDatabase;

  public static DatabaseFactory getInstance(Context context) {
    synchronized (lock) {
      if (instance == null)
        instance = new DatabaseFactory(context.getApplicationContext());

      return instance;
    }
  }

  public static MmsSmsDatabase getMmsSmsDatabase(Context context) {
    return getInstance(context).mmsSmsDatabase;
  }

  public static ThreadDatabase getThreadDatabase(Context context) {
    return getInstance(context).thread;
  }

  public static MessageDatabase getSmsDatabase(Context context) {
    return getInstance(context).sms;
  }

  public static MessageDatabase getMmsDatabase(Context context) {
    return getInstance(context).mms;
  }

  public static AttachmentDatabase getAttachmentDatabase(Context context) {
    return getInstance(context).attachments;
  }

  public static MediaDatabase getMediaDatabase(Context context) {
    return getInstance(context).media;
  }

  public static IdentityDatabase getIdentityDatabase(Context context) {
    return getInstance(context).identityDatabase;
  }

  public static DraftDatabase getDraftDatabase(Context context) {
    return getInstance(context).draftDatabase;
  }

  public static PushDatabase getPushDatabase(Context context) {
    return getInstance(context).pushDatabase;
  }

  public static GroupDatabase getGroupDatabase(Context context) {
    return getInstance(context).groupDatabase;
  }

  public static RecipientDatabase getRecipientDatabase(Context context) {
    return getInstance(context).recipientDatabase;
  }

  public static ContactsDatabase getContactsDatabase(Context context) {
    return getInstance(context).contactsDatabase;
  }

  public static GroupReceiptDatabase getGroupReceiptDatabase(Context context) {
    return getInstance(context).groupReceiptDatabase;
  }

  public static OneTimePreKeyDatabase getPreKeyDatabase(Context context) {
    return getInstance(context).preKeyDatabase;
  }

  public static SignedPreKeyDatabase getSignedPreKeyDatabase(Context context) {
    return getInstance(context).signedPreKeyDatabase;
  }

  public static SessionDatabase getSessionDatabase(Context context) {
    return getInstance(context).sessionDatabase;
  }

  public static SearchDatabase getSearchDatabase(Context context) {
    return getInstance(context).searchDatabase;
  }

  public static JobDatabase getJobDatabase(Context context) {
    return getInstance(context).jobDatabase;
  }

  public static StickerDatabase getStickerDatabase(Context context) {
    return getInstance(context).stickerDatabase;
  }

  public static StorageKeyDatabase getStorageKeyDatabase(Context context) {
    return getInstance(context).storageKeyDatabase;
  }

  public static KeyValueDatabase getKeyValueDatabase(Context context) {
    return getInstance(context).keyValueDatabase;
  }

  public static MegaphoneDatabase getMegaphoneDatabase(Context context) {
    return getInstance(context).megaphoneDatabase;
  }

  static RemappedRecordsDatabase getRemappedRecordsDatabase(Context context) {
    return getInstance(context).remappedRecordsDatabase;
  }

  public static MentionDatabase getMentionDatabase(Context context) {
    return getInstance(context).mentionDatabase;
  }

  public static SQLiteDatabase getBackupDatabase(Context context) {
    return getInstance(context).databaseHelper.getReadableDatabase();
  }

  public static void upgradeRestored(Context context, SQLiteDatabase database){
    synchronized (lock) {
      getInstance(context).databaseHelper.onUpgrade(database, database.getVersion(), -1);
      getInstance(context).databaseHelper.markCurrent(database);
      getInstance(context).mms.trimEntriesForExpiredMessages();

      instance.databaseHelper.close();
      instance = null;
    }
  }

  public static boolean inTransaction(Context context) {
    return getInstance(context).databaseHelper.getWritableDatabase().inTransaction();
  }

  private DatabaseFactory(@NonNull Context context) {
    SQLiteDatabase.loadLibs(context);

    DatabaseSecret      databaseSecret   = new DatabaseSecretProvider(context).getOrCreateDatabaseSecret();
    AttachmentSecret    attachmentSecret = AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret();

    this.databaseHelper          = new SQLCipherOpenHelper(context, databaseSecret);
    this.sms                     = new SmsDatabase(context, databaseHelper);
    this.mms                     = new MmsDatabase(context, databaseHelper);
    this.attachments             = new AttachmentDatabase(context, databaseHelper, attachmentSecret);
    this.media                   = new MediaDatabase(context, databaseHelper);
    this.thread                  = new ThreadDatabase(context, databaseHelper);
    this.mmsSmsDatabase          = new MmsSmsDatabase(context, databaseHelper);
    this.identityDatabase        = new IdentityDatabase(context, databaseHelper);
    this.draftDatabase           = new DraftDatabase(context, databaseHelper);
    this.pushDatabase            = new PushDatabase(context, databaseHelper);
    this.groupDatabase           = new GroupDatabase(context, databaseHelper);
    this.recipientDatabase       = new RecipientDatabase(context, databaseHelper);
    this.groupReceiptDatabase    = new GroupReceiptDatabase(context, databaseHelper);
    this.contactsDatabase        = new ContactsDatabase(context);
    this.preKeyDatabase          = new OneTimePreKeyDatabase(context, databaseHelper);
    this.signedPreKeyDatabase    = new SignedPreKeyDatabase(context, databaseHelper);
    this.sessionDatabase         = new SessionDatabase(context, databaseHelper);
    this.searchDatabase          = new SearchDatabase(context, databaseHelper);
    this.jobDatabase             = new JobDatabase(context, databaseHelper);
    this.stickerDatabase         = new StickerDatabase(context, databaseHelper, attachmentSecret);
    this.storageKeyDatabase      = new StorageKeyDatabase(context, databaseHelper);
    this.keyValueDatabase        = new KeyValueDatabase(context, databaseHelper);
    this.megaphoneDatabase       = new MegaphoneDatabase(context, databaseHelper);
    this.remappedRecordsDatabase = new RemappedRecordsDatabase(context, databaseHelper);
    this.mentionDatabase         = new MentionDatabase(context, databaseHelper);
  }

  public void triggerDatabaseAccess() {
    databaseHelper.getWritableDatabase();
  }
}
