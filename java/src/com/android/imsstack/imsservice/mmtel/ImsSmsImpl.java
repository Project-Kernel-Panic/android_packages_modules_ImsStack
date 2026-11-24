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

import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.imsstack.base.AppContext;
import com.android.imsstack.base.SystemServiceProxy.SmsManagerProxy;
import com.android.imsstack.base.TelephonyManagerProxy;
import com.android.imsstack.core.agents.AgentFactory;
import com.android.imsstack.core.agents.ConfigInterface;
import com.android.imsstack.core.config.CarrierConfig;
import com.android.imsstack.imsservice.mmtel.sms.SmsTransferLayer;
import com.android.imsstack.imsservice.mmtel.sms.SmsUtils;
import com.android.imsstack.util.ImsLog;
import com.android.imsstack.util.ImsUtils;
import com.android.imsstack.util.IndentingPrintWriter;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements ImsSmsImplBase to provide Sms over Ims feature.
 */
public final class ImsSmsImpl extends ImsSmsImplBase {
    private static final String TAG = "[GII-ImsSmsImpl]";
    private static final ConcurrentHashMap<Integer, String> sLearnedSmscBySubId =
            new ConcurrentHashMap<>();
    private static final ExecutorService sSmscExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ImsSmsSmscLoader");
        t.setDaemon(true);
        return t;
    });

    private final ImsCallContext mCallContext;
    private SmsTransferLayer mSmsTL = null;
    private volatile String mScAddress = null;
    private volatile String mPsiNumber = null;
    private final AtomicBoolean mScRefreshInFlight = new AtomicBoolean(false);
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

    /**
     * Records a carrier SMSC address observed in incoming MT RP-DATA RP-OA for fallback MO use.
     */
    public static void recordLearnedSmsc(int subId, String origAddr) {
        String cleaned = sanitizeSmscNumber(origAddr);
        if (subId >= 0 && cleaned != null && cleaned.length() >= 3) {
            sLearnedSmscBySubId.put(subId, cleaned);
        }
    }

    private static String sanitizeSmscNumber(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String s = raw.trim();
        int commaIdx = s.indexOf(',');
        if (commaIdx > 0) {
            s = s.substring(0, commaIdx).trim();
        }
        if (s.length() >= 2 && s.charAt(0) == 34 && s.charAt(s.length() - 1) == 34) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (TextUtils.isEmpty(s) || "00".equals(s)) {
            return null;
        }
        return s;
    }

    private static String extractNumberFromUri(Uri uri) {
        if (uri == null || Uri.EMPTY.equals(uri)) {
            return null;
        }
        String scheme = uri.getScheme();
        String ssp = uri.getSchemeSpecificPart();
        if (TextUtils.isEmpty(ssp)) {
            return null;
        }
        if ("tel".equalsIgnoreCase(scheme)) {
            int semiIdx = ssp.indexOf(';');
            return sanitizeSmscNumber(semiIdx > 0 ? ssp.substring(0, semiIdx) : ssp);
        }
        if ("sip".equalsIgnoreCase(scheme) || "sips".equalsIgnoreCase(scheme)) {
            int atIdx = ssp.indexOf('@');
            String user = atIdx > 0 ? ssp.substring(0, atIdx) : ssp;
            int semiIdx = user.indexOf(';');
            user = semiIdx > 0 ? user.substring(0, semiIdx) : user;
            if (!TextUtils.isEmpty(user) && (user.startsWith("+") || TextUtils.isDigitsOnly(user))) {
                return sanitizeSmscNumber(user);
            }
        }
        return null;
    }

    private static String encodeSmscBcd(String smscNumber) {
        String cleaned = sanitizeSmscNumber(smscNumber);
        if (cleaned == null) {
            return null;
        }
        try {
            byte[] scBytes = PhoneNumberUtils.networkPortionToCalledPartyBCDWithLength(cleaned);
            if (scBytes != null && scBytes.length >= 2 && (scBytes[0] & 0xff) >= 1) {
                return ImsUtils.bytesToHexString(scBytes);
            }
        } catch (Exception e) {
            loge("Failed to encode SMSC " + cleaned + ": " + e.getMessage());
        }
        return null;
    }

    private void refreshScAddressAsync() {
        if (mCallContext == null || !mScRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            AppContext.getInstance();
        } catch (IllegalStateException e) {
            mScRefreshInFlight.set(false);
            return;
        }
        final int subId = mCallContext.getSubId();
        sSmscExecutor.execute(() -> {
            try {
                if (subId >= 0) {
                    SmsManagerProxy smp = AppContext.getSmsManagerProxy(subId);
                    String simSmsc = sanitizeSmscNumber(smp.getSmscAddress());
                    if (simSmsc != null) {
                        mScAddress = simSmsc;
                        log("refreshScAddressAsync: cached SIM SMSC");
                    }
                    String psiNum = extractNumberFromUri(smp.getSmscIdentity());
                    if (psiNum != null) {
                        mPsiNumber = psiNum;
                        log("refreshScAddressAsync: cached PSI SMSC");
                    }
                }
            } catch (Exception e) {
                log("refreshScAddressAsync: " + e.getMessage());
            } finally {
                mScRefreshInFlight.set(false);
            }
        });
    }

    private String getConfiguredDefaultSmsc() {
        try {
            if (mCallContext == null) {
                return null;
            }
            ConfigInterface config = AgentFactory.getInstance().getAgent(
                    ConfigInterface.class, mCallContext.getSlotId());
            if (config == null) {
                return null;
            }
            CarrierConfig cc = config.getCarrierConfig();
            if (cc == null) {
                return null;
            }
            return sanitizeSmscNumber(cc.getString(CarrierConfig.ImsSms.KEY_SMS_DEFAULT_SMSC_STRING));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveSmsc(String smsc) {
        if (!TextUtils.isEmpty(smsc) && !"00".equals(smsc)) {
            return smsc;
        }
        String encoded = encodeSmscBcd(mScAddress);
        if (encoded != null) {
            return encoded;
        }
        if (isKoreanCarrier()) {
            log("sendSms: Korean carrier (MCC 450) - defaulting smsc to 0791280101010000 (+821010100000)");
            return "0791280101010000";
        }
        int subId = (mCallContext != null) ? mCallContext.getSubId() : -1;
        if (subId >= 0) {
            encoded = encodeSmscBcd(sLearnedSmscBySubId.get(subId));
            if (encoded != null) {
                log("sendSms: using network-learned MT SMSC");
                return encoded;
            }
        }
        encoded = encodeSmscBcd(getConfiguredDefaultSmsc());
        if (encoded != null) {
            log("sendSms: using carrier default SMSC");
            return encoded;
        }
        encoded = encodeSmscBcd(mPsiNumber);
        if (encoded != null) {
            log("sendSms: using PSI SMSC");
            return encoded;
        }
        refreshScAddressAsync();
        log("sendSms: International carrier - defaulting smsc to 00");
        return "00";
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
            String smsc = resolveSmsc(null);
            log("SMMA smsc = " + smsc);
            result = mSmsTL.sendMemoryAvailabilityNotification(token, smsc);
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

            if (smsc == null || smsc.length() == 0 || "00".equals(smsc)) {
                smsc = resolveSmsc(smsc);
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
        refreshScAddressAsync();
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
