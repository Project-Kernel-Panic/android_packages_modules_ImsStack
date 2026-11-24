/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.imsstack.imsservice.mmtel;

import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import com.android.imsstack.base.TelephonyManagerProxy;
import android.telephony.ims.stub.ImsSmsImplBase;
import com.android.imsstack.util.ImsUtils;

import androidx.annotation.NonNull;

import com.android.imsstack.base.AppContext;
import com.android.imsstack.base.SystemServiceProxy.SmsManagerProxy;
import com.android.imsstack.imsservice.mmtel.sms.SmsTransferLayer;
import com.android.imsstack.imsservice.mmtel.sms.SmsUtils;
import com.android.imsstack.util.ImsLog;
import com.android.imsstack.util.IndentingPrintWriter;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Implements ImsSmsImplBase to provide Sms over Ims feature.
 */
public final class ImsSmsImpl extends ImsSmsImplBase {
    private static final String TAG = "[GII-ImsSmsImpl]";
    private final ImsCallContext mCallContext;
    private SmsTransferLayer mSmsTL = null;
    private String mScAddress = null;
    private final SmsTLListenerProxy mSmsTLListener = new SmsTLListenerProxy();
    private boolean mReady = false;
    private final Object mLock = new Object();

    public ImsSmsImpl(ImsCallContext callContext) {
        mCallContext = callContext;
        init();
    }

    @VisibleForTesting
    public ImsSmsImpl(ImsCallContext callContext, SmsTransferLayer smsTransferLayer,
                                String scAddress) {
        mCallContext = callContext;
        mScAddress = scAddress;
        mSmsTL = smsTransferLayer;

        if (mSmsTL != null) {
            mSmsTL.setListener(mSmsTLListener);
        }
    }

    /**
     * Disposes the Object
     */
    public void dispose() {
        clear();
    }

    private boolean isKoreanCarrier() {
        try {
            TelephonyManagerProxy tmp =
                    AppContext.getTelephonyManagerProxy(mCallContext.getSubId());
            if (tmp != null) {
                String simOp = tmp.getSimOperator();
                if (simOp != null && simOp.startsWith("450")) {
                    return true;
                }
            }
            TelephonyManager tm = AppContext.getInstance().getSystemService(TelephonyManager.class);
            if (tm != null) {
                String simOp = tm.getSimOperator(mCallContext.getSubId());
                if (simOp != null && simOp.startsWith("450")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void ensureSmsTL() {
        synchronized (mLock) {
            if (mSmsTL == null) {
                log("ensureSmsTL: initializing SmsTransferLayer");
                mSmsTL = new SmsTransferLayer(mCallContext);
                mSmsTL.setListener(mSmsTLListener);
            }
        }
    }

    /**
     * Initialises the objects created by this Class
     */
    public void init() {
        ensureSmsTL();
        onReady();
    }

    /**
     * clears the objects created by this class.
     */
    public void clear() {
        synchronized (mLock) {
            mReady = false;
            if (mSmsTL != null) {
                log("clear");
                mSmsTL.setListener(null);
                mSmsTL.clear();
                mSmsTL = null;
            }
        }
    }

    @Override
    public void onMemoryAvailable(int token) {
        //send SMMA
        log("SMMA token = " + token);
        int result;
        if (!mReady) {
            throw new RuntimeException("Sms Not Ready!");
        }
        try {
            if (mScAddress == null || mScAddress.length() == 0) {
                mScAddress = "00";
            }
            log("SMMA smsc = " + mScAddress);
            result = mSmsTL.sendMemoryAvailabilityNotification(token, mScAddress);
        } catch (RuntimeException e) {
            loge("onMemoryAvailable :: Can not send smma: " + e.getMessage());
        }

    }

    @Override
    public void sendSms(
            int token, int messageRef, String format, String smsc, boolean isRetry, byte[] pdu) {
        log("sendSms: token = " + token + " format = " + format + " smsc = " + smsc
                                + " isRetry = " + isRetry);
        int smsFormat = SmsUtils.FORMAT_INT_INVALID;

        ensureSmsTL();
        SmsTransferLayer smsTL;
        synchronized (mLock) {
            if (!mReady || mSmsTL == null) {
                loge("sendSms failed: mReady=" + mReady + ", mSmsTL is null: " + (mSmsTL == null));
                // Gracefully report error to Framework so it can retry
                onSendSmsResultError(
                        token,
                        messageRef,
                        SEND_STATUS_ERROR_RETRY,
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                        RESULT_NO_NETWORK_ERROR);
                return;
            }
            smsTL = mSmsTL;
        }

        try {
            if (format.equals(SmsMessage.FORMAT_3GPP)) {
                smsFormat = SmsUtils.FORMAT_INT_3GPP;
            } else if (format.equals(SmsMessage.FORMAT_3GPP2)) {
                smsFormat = SmsUtils.FORMAT_INT_3GPP2;
            } else {
                loge("Invalid SMS Format");
                onSendSmsResultError(
                        token,
                        messageRef,
                        SEND_STATUS_ERROR,
                        SmsManager.RESULT_INVALID_SMS_FORMAT,
                        RESULT_NO_NETWORK_ERROR);
                return;
            }

            if (pdu == null) {
                loge("Pdu is null");
                onSendSmsResultError(
                        token,
                        messageRef,
                        SEND_STATUS_ERROR,
                        SmsManager.RESULT_ERROR_NULL_PDU,
                        RESULT_NO_NETWORK_ERROR);
                return;

            }

            if (smsc == null || smsc.length() == 0) {
                if (mScAddress != null && mScAddress.length() > 0) {
                    try {
                        byte[] scBytes =
                                PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(mScAddress);
                        if (scBytes != null && scBytes.length > 0) {
                            smsc = ImsUtils.bytesToHexString(scBytes);
                        }
                    } catch (Exception e) {
                        loge("Failed to encode mScAddress: " + e.getMessage());
                    }
                }
                if (smsc == null || smsc.length() == 0 || "00".equals(smsc)) {
                    if (isKoreanCarrier()) {
                        log("sendSms: Korean carrier (MCC 450) - defaulting smsc to 0791280101010000 (+821010100000)");
                        smsc = "0791280101010000";
                    } else {
                        log("sendSms: International carrier - defaulting smsc to 00");
                        smsc = "00";
                    }
                }
            }
            int result = smsTL.sendMoTPdu(token, smsFormat, messageRef, smsc, pdu, isRetry);
            if (result == SmsUtils.SMS_RESULT_INVALID_SMSC_ADDRESS) {
                loge("Can not send sms - Invalid smsc");
                onSendSmsResultError(
                            token,
                            messageRef,
                            SEND_STATUS_ERROR,
                            SmsManager.RESULT_INVALID_SMSC_ADDRESS,
                            RESULT_NO_NETWORK_ERROR);
            } else if (result == SmsUtils.SMSRL_RESULT_PDU_ENCODING_FAILED) {
                loge("Can not send sms - Encoding Failed");
                onSendSmsResultError(
                            token,
                            messageRef,
                            SEND_STATUS_ERROR,
                            SmsManager.RESULT_ENCODING_ERROR,
                            RESULT_NO_NETWORK_ERROR);
            } else if (result == SmsUtils.SMSRL_RESULT_MTS_CONTROLLER_FAILED) {
                //TODO: b/234531121 change the MtsController Failure codes
                loge("Can not send sms - Failure from MtsController");
                onSendSmsResultError(
                            token,
                            messageRef,
                            SEND_STATUS_ERROR,
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
                            RESULT_NO_NETWORK_ERROR);
            }
        } catch (RuntimeException e) {
            loge("sendSms failed: " + e.getMessage());
        }
    }

    @Override
    public void acknowledgeSms(int token, int messageRef, int result) {
        acknowledgeSms(token, messageRef, result, null);
    }

    @Override
    public void acknowledgeSms(int token, int messageRef,
            int result, byte[] pdu) {
        log("acknowledgeSms: token = " + token + " messageRef = " + messageRef
                + " result = " + result);
        if (!mReady) {
            throw new RuntimeException("Sms Not Ready!");
        }
        try {
            int tlResult = mSmsTL.sendReportTPdu(token, messageRef, result, pdu);
            if (tlResult != SmsUtils.RESULT_SUCCESS) {
                loge("Sending Acknowledge Failed");
            }
        } catch (RuntimeException e) {
            loge("acknowledgeSmsWithPdu Failed: " + e.getMessage());
        }
    }

    @Override
    public void acknowledgeSmsReport(int token, int messageRef, int result) {
        log("acknowledgeSmsReport: token = " + token + " messageRef = " + messageRef
                + " result = " + result);
        if (!mReady) {
            throw new RuntimeException("Sms Not Ready!");
        }
        try {
            int tlResult = mSmsTL.sendReportTPdu(token, messageRef, result, null);
            if (tlResult != SmsUtils.RESULT_SUCCESS) {
                loge("Sending Acknowledge Failed");
            }
        } catch (RuntimeException e) {
            loge("acknowledgeSmsReport Failed: " + e.getMessage());
        }
    }

    @Override
    public void onReady() {
        ensureSmsTL();
        synchronized (mLock) {
            mReady = true;
            log("Sms Ready");
        }
    }

    /**
     * Dump this instance into a readable format for dumpsys usage.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {
        pw.println("Sms:");
        pw.increaseIndent();

        pw.decreaseIndent();
    }

    private static void log(String s) {
        ImsLog.d(TAG + s);
    }

    private static void loge(String s) {
        ImsLog.e(TAG + s);
    }

    private class SmsTLListenerProxy implements SmsTransferLayer.Listener {

        @Override
        public void notifySmsResult(int token, int messageRef, int result, int reason, int cause) {
            log("notifySmsResult: token = " + token + " TP message Reference = " + messageRef
                    + " result = " + result + " reason = " + " cause = " + cause);
            try {
                if (result == SEND_STATUS_OK) {
                    onSendSmsResultSuccess(token, messageRef);
                } else {
                    onSendSmsResultError(token, messageRef, result, reason, cause);
                }
            } catch (RuntimeException e) {
                loge("notifySmsResult Failed: " + e.getMessage());
            }
        }

        @Override
        public int notifySmsReceived(int token, int format, int messageType, byte[] pdu) {
            log("notifySmsReceived: format = " + format
                                        + " token = " + token
                                        + " messageType = " + messageType);
            try {
                int messageRef = pdu[2] & 0xff;
                if (messageType == SmsUtils.TP_SMS_DELIVER) {
                    onSmsReceived(token, SmsUtils.getFormatString(format), pdu);
                    return SmsUtils.RESULT_SUCCESS;
                } else if (messageType == SmsUtils.TP_SMS_STATUS_REPORT) {
                    onSmsStatusReportReceived(token, SmsUtils.getFormatString(format), pdu);
                    return SmsUtils.RESULT_SUCCESS;
                } else {
                    loge("Invalid Message Type");
                    return mSmsTL.sendReportTPdu(token, messageRef,
                                        ImsSmsImplBase.DELIVER_STATUS_ERROR_REQUEST_NOT_SUPPORTED,
                                        null);
                }
            } catch (RuntimeException e) {
                loge("notifySmsReceived Failed : " + e.getMessage());
                return SmsUtils.RESULT_FAILURE;
            }
        }

        @Override
        public void notifyMemoryAvailableResult(int token, int result, int cause) {
            log("notifyMemoryAvailableResult: token = " + token + " result = " + result
                    + " cause = " + cause);
            try {
                onMemoryAvailableResult(token, result, cause);
            } catch (RuntimeException e) {
                loge("notifyMemoryAvailableResult error: " + e.getMessage());
            }
        }
    }
}
