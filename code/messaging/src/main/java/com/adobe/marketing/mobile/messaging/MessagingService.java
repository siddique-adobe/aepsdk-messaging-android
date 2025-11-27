/*
  Copyright 2023 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.messaging;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import com.adobe.marketing.mobile.Event;
import com.adobe.marketing.mobile.EventSource;
import com.adobe.marketing.mobile.EventType;
import com.adobe.marketing.mobile.MessagingPushPayload;
import com.adobe.marketing.mobile.MobileCore;
import com.adobe.marketing.mobile.services.Log;
import com.adobe.marketing.mobile.util.JSONUtils;
import com.adobe.marketing.mobile.util.StringUtils;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This class is the entry point for all push notifications received from Firebase.
 *
 * <p>Once the MessagingService is registered in the AndroidManifest.xml. This class will
 * automatically handle display and tracking of Adobe Journey Optimizer push notifications.
 */
public class MessagingService extends FirebaseMessagingService {
    private static final String SELF_TAG = "MessagingService";
    private static final String XDM_KEY = "_xdm";

    @Override
    public void onNewToken(final @NonNull String token) {
        super.onNewToken(token);
        MobileCore.setPushIdentifier(token);
    }

    @Override
    public void onMessageReceived(final @NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        handleRemoteMessage(this, remoteMessage);
    }

    @SuppressLint("MissingPermission")
    public static boolean handleRemoteMessage(
            final @NonNull Context context, final @NonNull RemoteMessage remoteMessage) {
        if (!isAJONotification(remoteMessage)) {
            Log.debug(
                    MessagingPushConstants.LOG_TAG,
                    SELF_TAG,
                    "The received push message is not generated from Adobe Journey Optimizer."
                            + " Messaging extension is ignoring to display the push notification.");
            return false;
        }

        final MessagingPushPayload payload = new MessagingPushPayload(remoteMessage);
        final Notification notification = MessagingPushBuilder.build(payload, context);

        // display notification
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(context);

        // Check for POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                            context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.warning(
                        MessagingPushConstants.LOG_TAG,
                        SELF_TAG,
                        "POST_NOTIFICATIONS permission not granted. Cannot display notification.");
                return false;
            }
        }

        notificationManager.notify(remoteMessage.getMessageId().hashCode(), notification);

        // dispatch Push notification displayed event
        final HashMap<String, Object> notificationData = new HashMap<>(remoteMessage.getData());
        
        // Add decisioning data to the XDM if not present
        enhanceXdmWithDecisioning(notificationData);
        
        final Event pushNotificationReceivedEvent =
                new Event.Builder(
                                "Push Notification Displayed",
                                EventType.MESSAGING,
                                EventSource.RESPONSE_CONTENT)
                        .setEventData(notificationData)
                        .build();
        MobileCore.dispatchEvent(pushNotificationReceivedEvent);
        return true;
    }

    /**
     * Enhances the XDM data in the notification data with decisioning information, ensuring all
     * required fields are present.
     *
     * @param notificationData the notification data map to be enhanced
     */
    private static void enhanceXdmWithDecisioning(final HashMap<String, Object> notificationData) {
        if (notificationData == null || !notificationData.containsKey(XDM_KEY)) {
            return;
        }

        final String xdmString = (String) notificationData.get(XDM_KEY);
        if (StringUtils.isNullOrEmpty(xdmString)) {
            return;
        }

        try {
            final JSONObject xdmJson = new JSONObject(xdmString);
            final Map<String, Object> xdmMap = JSONUtils.toMap(xdmJson);

            if (xdmMap == null) {
                return;
            }

            // Ensure decisioning data structure is complete
            ensureDecisioningInXdm(xdmMap);

            // Convert back to JSON string and update notificationData
            final JSONObject updatedXdmJson = new JSONObject(xdmMap);
            notificationData.put(XDM_KEY, updatedXdmJson.toString());

            Log.debug(
                    MessagingPushConstants.LOG_TAG,
                    SELF_TAG,
                    "Successfully ensured decisioning data structure in display event XDM.");
        } catch (final JSONException | ClassCastException e) {
            Log.warning(
                    MessagingPushConstants.LOG_TAG,
                    SELF_TAG,
                    "Failed to enhance XDM with decisioning data: %s",
                    e.getMessage());
        }
    }

    /**
     * Ensures decisioning structure is complete in the XDM map, adding missing fields.
     *
     * @param xdmMap the XDM map to be validated and updated
     */
    private static void ensureDecisioningInXdm(final Map<String, Object> xdmMap) {
        // Get or create the mixins/cjm map
        Map<String, Object> mixins = null;
        String mixinsKey = null;

        if (xdmMap.containsKey(MessagingConstants.TrackingKeys.CJM)) {
            mixinsKey = MessagingConstants.TrackingKeys.CJM;
            Object cjmObj = xdmMap.get(MessagingConstants.TrackingKeys.CJM);
            if (cjmObj instanceof Map) {
                mixins = (Map<String, Object>) cjmObj;
            }
        } else if (xdmMap.containsKey(MessagingConstants.TrackingKeys.MIXINS)) {
            mixinsKey = MessagingConstants.TrackingKeys.MIXINS;
            Object mixinsObj = xdmMap.get(MessagingConstants.TrackingKeys.MIXINS);
            if (mixinsObj instanceof Map) {
                mixins = (Map<String, Object>) mixinsObj;
            }
        }

        // If no mixins found, create cjm
        if (mixins == null) {
            mixins = new HashMap<>();
            mixinsKey = MessagingConstants.TrackingKeys.CJM;
            xdmMap.put(mixinsKey, mixins);
        }

        // Get or create _experience
        Map<String, Object> experience = null;
        if (mixins.containsKey(MessagingConstants.TrackingKeys.EXPERIENCE)) {
            Object expObj = mixins.get(MessagingConstants.TrackingKeys.EXPERIENCE);
            if (expObj instanceof Map) {
                experience = (Map<String, Object>) expObj;
            }
        }

        if (experience == null) {
            experience = new HashMap<>();
            mixins.put(MessagingConstants.TrackingKeys.EXPERIENCE, experience);
        }

        // Get existing decisioning or create new
        Map<String, Object> decisioning = null;
        if (experience.containsKey(MessagingConstants.TrackingKeys.DECISIONING)
                && experience.get(MessagingConstants.TrackingKeys.DECISIONING) instanceof Map) {
            decisioning = (Map<String, Object>) experience.get(MessagingConstants.TrackingKeys.DECISIONING);
            Log.debug(
                    MessagingPushConstants.LOG_TAG,
                    SELF_TAG,
                    "Decisioning object exists in display event, ensuring all required fields are"
                            + " present.");
        } else {
            decisioning = new HashMap<>();
        }

        // Ensure all required fields are present
        ensureDecisioningFields(decisioning);

        // Update decisioning in experience
        experience.put(MessagingConstants.TrackingKeys.DECISIONING, decisioning);
    }

    /**
     * Ensures all required fields are present in the decisioning map.
     *
     * @param decisioning the decisioning map to validate and populate
     */
    private static void ensureDecisioningFields(final Map<String, Object> decisioning) {
        // Ensure exdRequestID exists
        if (!decisioning.containsKey(MessagingConstants.TrackingKeys.EXD_REQUEST_ID)) {
            decisioning.put(MessagingConstants.TrackingKeys.EXD_REQUEST_ID, "");
        }

        // Ensure propositions array exists
        List<Map<String, Object>> propositions = null;
        if (decisioning.containsKey(MessagingConstants.TrackingKeys.PROPOSITIONS)
                && decisioning.get(MessagingConstants.TrackingKeys.PROPOSITIONS) instanceof List) {
            propositions = (List<Map<String, Object>>) decisioning.get(MessagingConstants.TrackingKeys.PROPOSITIONS);
        }

        if (propositions == null || propositions.isEmpty()) {
            propositions = new ArrayList<>();
            decisioning.put(MessagingConstants.TrackingKeys.PROPOSITIONS, propositions);
        }

        // Ensure at least one proposition with complete structure
        if (propositions.isEmpty()) {
            propositions.add(createDefaultProposition());
        } else {
            // Validate and fill each proposition
            for (Map<String, Object> proposition : propositions) {
                ensurePropositionFields(proposition);
            }
        }
    }

    /**
     * Ensures all required fields are present in a proposition.
     *
     * @param proposition the proposition map to validate and populate
     */
    private static void ensurePropositionFields(final Map<String, Object> proposition) {
        // Ensure id exists
        if (!proposition.containsKey(MessagingConstants.PayloadKeys.ID)) {
            proposition.put(MessagingConstants.PayloadKeys.ID, "");
        }

        // Ensure scopeDetails exists
        Map<String, Object> scopeDetails = null;
        if (proposition.containsKey(MessagingConstants.TrackingKeys.SCOPE_DETAILS)
                && proposition.get(MessagingConstants.TrackingKeys.SCOPE_DETAILS) instanceof Map) {
            scopeDetails = (Map<String, Object>) proposition.get(MessagingConstants.TrackingKeys.SCOPE_DETAILS);
        }

        if (scopeDetails == null) {
            scopeDetails = new HashMap<>();
            proposition.put(MessagingConstants.TrackingKeys.SCOPE_DETAILS, scopeDetails);
        }

        // Ensure scopeDetails fields
        if (!scopeDetails.containsKey(MessagingConstants.PayloadKeys.CORRELATION_ID)) {
            scopeDetails.put(MessagingConstants.PayloadKeys.CORRELATION_ID, "");
        }

        if (!scopeDetails.containsKey(MessagingConstants.TrackingKeys.DECISION_PROVIDER)) {
            scopeDetails.put(MessagingConstants.TrackingKeys.DECISION_PROVIDER, "EXD");
        }

        // Ensure placement exists
        Map<String, Object> placement = null;
        if (scopeDetails.containsKey(MessagingConstants.TrackingKeys.PLACEMENT)
                && scopeDetails.get(MessagingConstants.TrackingKeys.PLACEMENT) instanceof Map) {
            placement = (Map<String, Object>) scopeDetails.get(MessagingConstants.TrackingKeys.PLACEMENT);
        }

        if (placement == null) {
            placement = new HashMap<>();
            scopeDetails.put(MessagingConstants.TrackingKeys.PLACEMENT, placement);
        }

        if (!placement.containsKey(MessagingConstants.PayloadKeys.ID)) {
            placement.put(MessagingConstants.PayloadKeys.ID, "");
        }

        // Ensure items array exists
        List<Map<String, Object>> items = null;
        if (proposition.containsKey(MessagingConstants.TrackingKeys.ITEMS)
                && proposition.get(MessagingConstants.TrackingKeys.ITEMS) instanceof List) {
            items = (List<Map<String, Object>>) proposition.get(MessagingConstants.TrackingKeys.ITEMS);
        }

        if (items == null || items.isEmpty()) {
            items = new ArrayList<>();
            proposition.put(MessagingConstants.TrackingKeys.ITEMS, items);
        }

        // Ensure at least one item with complete structure
        if (items.isEmpty()) {
            items.add(createDefaultItem());
        } else {
            // Validate and fill each item
            for (Map<String, Object> item : items) {
                ensureItemFields(item);
            }
        }
    }

    /**
     * Ensures all required fields are present in an item.
     *
     * @param item the item map to validate and populate
     */
    private static void ensureItemFields(final Map<String, Object> item) {
        // Ensure id exists
        if (!item.containsKey(MessagingConstants.PayloadKeys.ID)) {
            item.put(MessagingConstants.PayloadKeys.ID, "");
        }

        // Ensure itemSelection exists
        Map<String, Object> itemSelection = null;
        if (item.containsKey(MessagingConstants.TrackingKeys.ITEM_SELECTION)
                && item.get(MessagingConstants.TrackingKeys.ITEM_SELECTION) instanceof Map) {
            itemSelection = (Map<String, Object>) item.get(MessagingConstants.TrackingKeys.ITEM_SELECTION);
        }

        if (itemSelection == null) {
            itemSelection = new HashMap<>();
            item.put(MessagingConstants.TrackingKeys.ITEM_SELECTION, itemSelection);
        }

        // Ensure rankingDetail exists
        Map<String, Object> rankingDetail = null;
        if (itemSelection.containsKey(MessagingConstants.TrackingKeys.RANKING_DETAIL)
                && itemSelection.get(MessagingConstants.TrackingKeys.RANKING_DETAIL) instanceof Map) {
            rankingDetail = (Map<String, Object>) itemSelection.get(MessagingConstants.TrackingKeys.RANKING_DETAIL);
        }

        if (rankingDetail == null) {
            rankingDetail = new HashMap<>();
            itemSelection.put(MessagingConstants.TrackingKeys.RANKING_DETAIL, rankingDetail);
        }

        // Ensure rankingDetail fields
        if (!rankingDetail.containsKey(MessagingConstants.TrackingKeys.STRATEGY_ID)) {
            rankingDetail.put(MessagingConstants.TrackingKeys.STRATEGY_ID, "");
        }

        if (!rankingDetail.containsKey(MessagingConstants.TrackingKeys.STEP)) {
            rankingDetail.put(MessagingConstants.TrackingKeys.STEP, "");
        }

        if (!rankingDetail.containsKey(MessagingConstants.TrackingKeys.TREATMENT_ID)) {
            rankingDetail.put(MessagingConstants.TrackingKeys.TREATMENT_ID, "");
        }
    }

    /**
     * Creates a default proposition structure.
     *
     * @return a Map containing default proposition structure
     */
    private static Map<String, Object> createDefaultProposition() {
        final Map<String, Object> proposition = new HashMap<>();
        proposition.put(MessagingConstants.PayloadKeys.ID, "");

        final Map<String, Object> scopeDetails = new HashMap<>();
        scopeDetails.put(MessagingConstants.PayloadKeys.CORRELATION_ID, "");
        scopeDetails.put(MessagingConstants.TrackingKeys.DECISION_PROVIDER, "EXD");

        final Map<String, Object> placement = new HashMap<>();
        placement.put(MessagingConstants.PayloadKeys.ID, "");
        scopeDetails.put(MessagingConstants.TrackingKeys.PLACEMENT, placement);

        proposition.put(MessagingConstants.TrackingKeys.SCOPE_DETAILS, scopeDetails);

        final List<Map<String, Object>> items = new ArrayList<>();
        items.add(createDefaultItem());
        proposition.put(MessagingConstants.TrackingKeys.ITEMS, items);

        return proposition;
    }

    /**
     * Creates a default item structure.
     *
     * @return a Map containing default item structure
     */
    private static Map<String, Object> createDefaultItem() {
        final Map<String, Object> item = new HashMap<>();
        item.put(MessagingConstants.PayloadKeys.ID, "");

        final Map<String, Object> itemSelection = new HashMap<>();
        final Map<String, Object> rankingDetail = new HashMap<>();
        rankingDetail.put(MessagingConstants.TrackingKeys.STRATEGY_ID, "");
        rankingDetail.put(MessagingConstants.TrackingKeys.STEP, "");
        rankingDetail.put(MessagingConstants.TrackingKeys.TREATMENT_ID, "");
        itemSelection.put(MessagingConstants.TrackingKeys.RANKING_DETAIL, rankingDetail);
        item.put(MessagingConstants.TrackingKeys.ITEM_SELECTION, itemSelection);

        return item;
    }


    /**
     * This method looks at the remote message payload and determines if it is a notification from
     * Adobe Journey Optimizer.
     *
     * @param remoteMessage the message received from Firebase
     * @return true if the remote message originated from Adobe Journey Optimizer, false otherwise
     */
    private static boolean isAJONotification(final @NonNull RemoteMessage remoteMessage) {
        // TODO: Use the newly introduced key "ajo_type" to identify Adobe push notifications.
        return remoteMessage.getData().containsKey(XDM_KEY)
                || remoteMessage.getData().containsKey(MessagingConstants.Push.PayloadKeys.TITLE);
    }
}
