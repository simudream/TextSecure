package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirement;
import org.thoughtcrime.securesms.mms.ApnUnavailableException;
import org.thoughtcrime.securesms.mms.MediaConstraints;
import org.thoughtcrime.securesms.mms.MmsRadio;
import org.thoughtcrime.securesms.mms.MmsRadioException;
import org.thoughtcrime.securesms.mms.MmsSendResult;
import org.thoughtcrime.securesms.mms.OutgoingMmsConnection;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.transport.InsecureFallbackApprovalException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.NumberUtil;
import org.thoughtcrime.securesms.util.TelephonyUtil;
import org.thoughtcrime.securesms.util.SmilUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;

import java.io.IOException;
import java.util.Arrays;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.EncodedStringValue;
import ws.com.google.android.mms.pdu.PduComposer;
import ws.com.google.android.mms.pdu.PduHeaders;
import ws.com.google.android.mms.pdu.SendConf;
import ws.com.google.android.mms.pdu.SendReq;

public class MmsSendJob extends SendJob {

  private static final String TAG = MmsSendJob.class.getSimpleName();

  private final long messageId;

  public MmsSendJob(Context context, long messageId) {
    super(context, JobParameters.newBuilder()
                                .withGroupId("mms-operation")
                                .withRequirement(new NetworkRequirement(context))
                                .withRequirement(new MasterSecretRequirement(context))
                                .withPersistence()
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.markAsSending(messageId);
  }

  @Override
  public void onSend(MasterSecret masterSecret) throws MmsException, NoSuchMessageException, IOException {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    SendReq     message  = database.getOutgoingMessage(masterSecret, messageId);

    try {
      MmsSendResult result = deliver(masterSecret, message);

      database.markAsSent(messageId, result.getMessageId(), result.getResponseStatus());
    } catch (UndeliverableMessageException e) {
      Log.w(TAG, e);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (InsecureFallbackApprovalException e) {
      Log.w(TAG, e);
      database.markAsPendingInsecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  public MmsSendResult deliver(MasterSecret masterSecret, SendReq message)
      throws UndeliverableMessageException, InsecureFallbackApprovalException
  {

    validateDestinations(message);

    MmsRadio radio = MmsRadio.getInstance(context);

    try {
      prepareMessageMedia(masterSecret, message, MediaConstraints.MMS_CONSTRAINTS, true);
      if (isCdmaDevice()) {
        Log.w(TAG, "Sending MMS directly without radio change...");
        try {
          return sendMms(masterSecret, radio, message, false, false);
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      Log.w(TAG, "Sending MMS with radio change and proxy...");
      radio.connect();

      try {
        try {
          return sendMms(masterSecret, radio, message, true, true);
        } catch (IOException e) {
          Log.w(TAG, e);
        }

        Log.w(TAG, "Sending MMS with radio change and without proxy...");

        try {
          return sendMms(masterSecret, radio, message, true, false);
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
          throw new UndeliverableMessageException(ioe);
        }
      } finally {
        radio.disconnect();
      }

    } catch (MmsRadioException | IOException e) {
      Log.w(TAG, e);
      throw new UndeliverableMessageException(e);
    }
  }

  private MmsSendResult sendMms(MasterSecret masterSecret, MmsRadio radio, SendReq message,
                                boolean usingMmsRadio, boolean useProxy)
      throws IOException, UndeliverableMessageException, InsecureFallbackApprovalException
  {
    String number = TelephonyUtil.getManager(context).getLine1Number();

    if (MmsDatabase.Types.isSecureType(message.getDatabaseMessageBox())) {
      throw new UndeliverableMessageException("Attempt to send encrypted MMS?");
    }

    if (number != null && number.trim().length() != 0) {
      message.setFrom(new EncodedStringValue(number));
    }

    try {
      byte[] pdu = new PduComposer(context, message).make();

      if (pdu == null) {
        throw new UndeliverableMessageException("PDU composition failed, null payload");
      }

      OutgoingMmsConnection connection = new OutgoingMmsConnection(context, radio.getApnInformation(), pdu);
      SendConf              conf       = connection.send(usingMmsRadio, useProxy);

      if (conf == null) {
        throw new UndeliverableMessageException("No M-Send.conf received in response to send.");
      } else if (conf.getResponseStatus() != PduHeaders.RESPONSE_STATUS_OK) {
        throw new UndeliverableMessageException("Got bad response: " + conf.getResponseStatus());
      } else if (isInconsistentResponse(message, conf)) {
        throw new UndeliverableMessageException("Mismatched response!");
      } else {
        return new MmsSendResult(conf.getMessageId(), conf.getResponseStatus());
      }
    } catch (ApnUnavailableException aue) {
      throw new IOException("no APN was retrievable");
    }
  }

  private boolean isInconsistentResponse(SendReq message, SendConf response) {
    Log.w(TAG, "Comparing: " + Hex.toString(message.getTransactionId()));
    Log.w(TAG, "With:      " + Hex.toString(response.getTransactionId()));
    return !Arrays.equals(message.getTransactionId(), response.getTransactionId());
  }

  private boolean isCdmaDevice() {
    return ((TelephonyManager)context
        .getSystemService(Context.TELEPHONY_SERVICE))
        .getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA;
  }

  private void validateDestination(EncodedStringValue destination) throws UndeliverableMessageException {
    if (destination == null || !NumberUtil.isValidSmsOrEmail(destination.getString())) {
      throw new UndeliverableMessageException("Invalid destination: " +
                                                  (destination == null ? null : destination.getString()));
    }
  }

  private void validateDestinations(SendReq message) throws UndeliverableMessageException {
    if (message.getTo() != null) {
      for (EncodedStringValue to : message.getTo()) {
        validateDestination(to);
      }
    }

    if (message.getCc() != null) {
      for (EncodedStringValue cc : message.getCc()) {
        validateDestination(cc);
      }
    }

    if (message.getBcc() != null) {
      for (EncodedStringValue bcc : message.getBcc()) {
        validateDestination(bcc);
      }
    }

    if (message.getTo() == null && message.getCc() == null && message.getBcc() == null) {
      throw new UndeliverableMessageException("No to, cc, or bcc specified!");
    }
  }

  private void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long       threadId   = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    if (recipients != null) {
      MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
    }
  }

  @Override
  protected void prepareMessageMedia(MasterSecret masterSecret, SendReq message,
                                     MediaConstraints constraints, boolean toMemory)
      throws IOException, UndeliverableMessageException {
    super.prepareMessageMedia(masterSecret, message, constraints, toMemory);
    message.setBody(SmilUtil.getSmilBody(message.getBody()));
  }

}
