package org.thoughtcrime.securesms.backup;


import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import androidx.annotation.NonNull;
import android.util.Pair;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.backup.BackupProtos.Attachment;
import org.thoughtcrime.securesms.backup.BackupProtos.BackupFrame;
import org.thoughtcrime.securesms.backup.BackupProtos.DatabaseVersion;
import org.thoughtcrime.securesms.backup.BackupProtos.SharedPreference;
import org.thoughtcrime.securesms.backup.BackupProtos.SqlStatement;
import org.thoughtcrime.securesms.backup.BackupProtos.Sticker;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.crypto.EncryptedPreferences;
import org.thoughtcrime.securesms.crypto.ModernEncryptingPartOutputStream;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.SearchDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Conversions;
import org.thoughtcrime.securesms.util.SqlUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.kdf.HKDFv3;
import org.whispersystems.libsignal.util.ByteUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FullBackupImporter extends FullBackupBase {

  @SuppressWarnings("unused")
  private static final String TAG = FullBackupImporter.class.getSimpleName();

  public static void importFile(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret,
                                @NonNull SQLiteDatabase db, @NonNull File file, @NonNull String passphrase)
      throws IOException
  {
    BackupRecordInputStream inputStream = new BackupRecordInputStream(file, passphrase);
    int                     count       = 0;

    try {
      db.beginTransaction();

      dropAllTables(db);

      BackupFrame frame;

      while (!(frame = inputStream.readFrame()).getEnd()) {
        if (count++ % 100 == 0) EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, count));

        if      (frame.hasVersion())    processVersion(db, frame.getVersion());
        else if (frame.hasStatement())  processStatement(db, frame.getStatement());
        else if (frame.hasPreference()) processPreference(context, frame.getPreference());
        else if (frame.hasAttachment()) processAttachment(context, attachmentSecret, db, frame.getAttachment(), inputStream);
        else if (frame.hasSticker())    processSticker(context, attachmentSecret, db, frame.getSticker(), inputStream);
        else if (frame.hasAvatar())     processAvatar(context, db, frame.getAvatar(), inputStream);
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, count));
  }

  private static void processVersion(@NonNull SQLiteDatabase db, DatabaseVersion version) throws IOException {
    if (version.getVersion() > db.getVersion()) {
      throw new DatabaseDowngradeException(db.getVersion(), version.getVersion());
    }

    db.setVersion(version.getVersion());
  }

  private static void processStatement(@NonNull SQLiteDatabase db, SqlStatement statement) {
    boolean isForSmsFtsSecretTable = statement.getStatement().contains(SearchDatabase.SMS_FTS_TABLE_NAME + "_");
    boolean isForMmsFtsSecretTable = statement.getStatement().contains(SearchDatabase.MMS_FTS_TABLE_NAME + "_");
    boolean isForSqliteSecretTable = statement.getStatement().toLowerCase().startsWith("create table sqlite_");

    if (isForSmsFtsSecretTable || isForMmsFtsSecretTable || isForSqliteSecretTable) {
      Log.i(TAG, "Ignoring import for statement: " + statement.getStatement());
      return;
    }

    List<Object> parameters = new LinkedList<>();

    for (SqlStatement.SqlParameter parameter : statement.getParametersList()) {
      if      (parameter.hasStringParamter())   parameters.add(parameter.getStringParamter());
      else if (parameter.hasDoubleParameter())  parameters.add(parameter.getDoubleParameter());
      else if (parameter.hasIntegerParameter()) parameters.add(parameter.getIntegerParameter());
      else if (parameter.hasBlobParameter())    parameters.add(parameter.getBlobParameter().toByteArray());
      else if (parameter.hasNullparameter())    parameters.add(null);
    }

    if (parameters.size() > 0) db.execSQL(statement.getStatement(), parameters.toArray());
    else                       db.execSQL(statement.getStatement());
  }

  private static void processAttachment(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret, @NonNull SQLiteDatabase db, @NonNull Attachment attachment, BackupRecordInputStream inputStream)
      throws IOException
  {
    File                       partsDirectory = context.getDir(AttachmentDatabase.DIRECTORY, Context.MODE_PRIVATE);
    File                       dataFile       = File.createTempFile("part", ".mms", partsDirectory);
    Pair<byte[], OutputStream> output         = ModernEncryptingPartOutputStream.createFor(attachmentSecret, dataFile, false);

    ContentValues contentValues = new ContentValues();

    try {
      inputStream.readAttachmentTo(output.second, attachment.getLength());

      contentValues.put(AttachmentDatabase.DATA, dataFile.getAbsolutePath());
      contentValues.put(AttachmentDatabase.THUMBNAIL, (String)null);
      contentValues.put(AttachmentDatabase.DATA_RANDOM, output.first);
    } catch (BadMacException e) {
      Log.w(TAG, "Bad MAC for attachment " + attachment.getAttachmentId() + "! Can't restore it.", e);
      dataFile.delete();
      contentValues.put(AttachmentDatabase.DATA, (String) null);
      contentValues.put(AttachmentDatabase.THUMBNAIL, (String) null);
      contentValues.put(AttachmentDatabase.DATA_RANDOM, (String) null);
    }

    db.update(AttachmentDatabase.TABLE_NAME, contentValues,
              AttachmentDatabase.ROW_ID + " = ? AND " + AttachmentDatabase.UNIQUE_ID + " = ?",
              new String[] {String.valueOf(attachment.getRowId()), String.valueOf(attachment.getAttachmentId())});
  }

  private static void processSticker(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret, @NonNull SQLiteDatabase db, @NonNull Sticker sticker, BackupRecordInputStream inputStream)
      throws IOException
  {
    File stickerDirectory = context.getDir(StickerDatabase.DIRECTORY, Context.MODE_PRIVATE);
    File dataFile         = File.createTempFile("sticker", ".mms", stickerDirectory);

    Pair<byte[], OutputStream> output = ModernEncryptingPartOutputStream.createFor(attachmentSecret, dataFile, false);

    inputStream.readAttachmentTo(output.second, sticker.getLength());

    ContentValues contentValues = new ContentValues();
    contentValues.put(StickerDatabase.FILE_PATH, dataFile.getAbsolutePath());
    contentValues.put(StickerDatabase.FILE_LENGTH, sticker.getLength());
    contentValues.put(StickerDatabase.FILE_RANDOM, output.first);

    db.update(StickerDatabase.TABLE_NAME, contentValues,
              StickerDatabase._ID + " = ?",
              new String[] {String.valueOf(sticker.getRowId())});
  }

  private static void processAvatar(@NonNull Context context, @NonNull SQLiteDatabase db, @NonNull BackupProtos.Avatar avatar, @NonNull BackupRecordInputStream inputStream) throws IOException {
    if (avatar.hasRecipientId()) {
      RecipientId recipientId = RecipientId.from(avatar.getRecipientId());
      inputStream.readAttachmentTo(AvatarHelper.getOutputStream(context, recipientId), avatar.getLength());
    } else {
      if (avatar.hasName() && SqlUtil.tableExists(db, "recipient_preferences")) {
        Log.w(TAG, "Avatar is missing a recipientId. Clearing signal_profile_avatar (legacy) so it can be fetched later.");
        db.execSQL("UPDATE recipient_preferences SET signal_profile_avatar = NULL WHERE recipient_ids = ?", new String[] { avatar.getName() });
      } else if (avatar.hasName() && SqlUtil.tableExists(db, "recipient")) {
        Log.w(TAG, "Avatar is missing a recipientId. Clearing signal_profile_avatar so it can be fetched later.");
        db.execSQL("UPDATE recipient SET signal_profile_avatar = NULL WHERE phone = ?", new String[] { avatar.getName() });
      } else {
        Log.w(TAG, "Avatar is missing a recipientId. Skipping avatar restore.");
      }

      inputStream.readAttachmentTo(new ByteArrayOutputStream(), avatar.getLength());
    }
  }

  @SuppressLint("ApplySharedPref")
  private static void processPreference(@NonNull Context context, SharedPreference preference) {
    SharedPreferences preferences = EncryptedPreferences.create(context, preference.getFile());
    preferences.edit().putString(preference.getKey(), preference.getValue()).commit();
  }

  private static void dropAllTables(@NonNull SQLiteDatabase db) {
    try (Cursor cursor = db.rawQuery("SELECT name, type FROM sqlite_master", null)) {
      while (cursor != null && cursor.moveToNext()) {
        String name = cursor.getString(0);
        String type = cursor.getString(1);

        if ("table".equals(type) && !name.startsWith("sqlite_")) {
          db.execSQL("DROP TABLE IF EXISTS " + name);
        }
      }
    }
  }

  private static class BackupRecordInputStream extends BackupStream {

    private final InputStream in;
    private final Cipher      cipher;
    private final Mac         mac;

    private final byte[] cipherKey;
    private final byte[] macKey;

    private byte[] iv;
    private int    counter;

    private BackupRecordInputStream(@NonNull File file, @NonNull String passphrase) throws IOException {
      try {
        this.in     = new FileInputStream(file);

        byte[] headerLengthBytes = new byte[4];
        Util.readFully(in, headerLengthBytes);

        int headerLength = Conversions.byteArrayToInt(headerLengthBytes);
        byte[] headerFrame = new byte[headerLength];
        Util.readFully(in, headerFrame);

        BackupFrame frame = BackupFrame.parseFrom(headerFrame);

        if (!frame.hasHeader()) {
          throw new IOException("Backup stream does not start with header!");
        }

        BackupProtos.Header header = frame.getHeader();

        this.iv = header.getIv().toByteArray();

        if (iv.length != 16) {
          throw new IOException("Invalid IV length!");
        }

        byte[]   key     = getBackupKey(passphrase, header.hasSalt() ? header.getSalt().toByteArray() : null);
        byte[]   derived = new HKDFv3().deriveSecrets(key, "Backup Export".getBytes(), 64);
        byte[][] split   = ByteUtil.split(derived, 32, 32);

        this.cipherKey = split[0];
        this.macKey    = split[1];

        this.cipher = Cipher.getInstance("AES/CTR/NoPadding");
        this.mac    = Mac.getInstance("HmacSHA256");
        this.mac.init(new SecretKeySpec(macKey, "HmacSHA256"));

        this.counter = Conversions.byteArrayToInt(iv);
      } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
        throw new AssertionError(e);
      }
    }

    BackupFrame readFrame() throws IOException {
      return readFrame(in);
    }

    void readAttachmentTo(OutputStream out, int length) throws IOException {
      try {
        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));
        mac.update(iv);

        byte[] buffer = new byte[8192];

        while (length > 0) {
          int read = in.read(buffer, 0, Math.min(buffer.length, length));
          if (read == -1) throw new IOException("File ended early!");

          mac.update(buffer, 0, read);

          byte[] plaintext = cipher.update(buffer, 0, read);

          if (plaintext != null) {
            out.write(plaintext, 0, plaintext.length);
          }

          length -= read;
        }

        byte[] plaintext = cipher.doFinal();

        if (plaintext != null) {
          out.write(plaintext, 0, plaintext.length);
        }

        out.close();

        byte[] ourMac   = ByteUtil.trim(mac.doFinal(), 10);
        byte[] theirMac = new byte[10];

        try {
          Util.readFully(in, theirMac);
        } catch (IOException e) {
          throw new IOException(e);
        }

        if (!MessageDigest.isEqual(ourMac, theirMac)) {
          throw new BadMacException();
        }
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }

    private BackupFrame readFrame(InputStream in) throws IOException {
      try {
        byte[] length = new byte[4];
        Util.readFully(in, length);

        byte[] frame = new byte[Conversions.byteArrayToInt(length)];
        Util.readFully(in, frame);

        byte[] theirMac = new byte[10];
        System.arraycopy(frame, frame.length - 10, theirMac, 0, theirMac.length);

        mac.update(frame, 0, frame.length - 10);
        byte[] ourMac = ByteUtil.trim(mac.doFinal(), 10);

        if (!MessageDigest.isEqual(ourMac, theirMac)) {
          throw new IOException("Bad MAC");
        }

        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));

        byte[] plaintext = cipher.doFinal(frame, 0, frame.length - 10);

        return BackupFrame.parseFrom(plaintext);
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static class BadMacException extends IOException {}

  public static class DatabaseDowngradeException extends IOException {
    DatabaseDowngradeException(int currentVersion, int backupVersion) {
      super("Tried to import a backup with version " + backupVersion + " into a database with version " + currentVersion);
    }
  }
}
