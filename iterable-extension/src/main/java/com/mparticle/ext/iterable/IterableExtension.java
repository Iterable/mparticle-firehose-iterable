package com.mparticle.ext.iterable;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.mparticle.iterable.*;
import com.mparticle.sdk.MessageProcessor;
import com.mparticle.sdk.model.audienceprocessing.Audience;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeRequest;
import com.mparticle.sdk.model.audienceprocessing.AudienceMembershipChangeResponse;
import com.mparticle.sdk.model.audienceprocessing.UserProfile;
import com.mparticle.sdk.model.eventprocessing.*;
import com.mparticle.sdk.model.registration.*;
import retrofit2.Response;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class IterableExtension extends MessageProcessor {

    public static final String NAME = "Iterable";
    public static final String SETTING_API_KEY = "apiKey";
    public static final String SETTING_GCM_NAME_KEY = "gcmIntegrationName";
    public static final String SETTING_APNS_KEY = "apnsProdIntegrationName";
    public static final String SETTING_APNS_SANDBOX_KEY = "apnsSandboxIntegrationName";
    public static final String SETTING_LIST_ID = "listId";
    public static final String SETTING_COERCE_STRINGS_TO_SCALARS = "coerceStringsToScalars";
    public static final String SETTING_USER_ID_FIELD = "userIdField";
    public static final String USER_ID_FIELD_CUSTOMER_ID = "customerId";
    public static final String USER_ID_FIELD_MPID = "mpid";
    IterableService iterableService;

    @Override
    public EventProcessingResponse processEventProcessingRequest(EventProcessingRequest request) throws IOException {
        if (iterableService == null) {
            iterableService = IterableService.newInstance();
        }
        Collections.sort(
                request.getEvents(),
                (a, b) -> a.getTimestamp() > b.getTimestamp() ? 1 : a.getTimestamp() == b.getTimestamp() ? 0 : -1
        );
        insertPlaceholderEmail(request);
        updateUser(request);
        processPushOpens(request);
        return super.processEventProcessingRequest(request);
    }

    private void processPushOpens(EventProcessingRequest processingRequest) throws IOException {
        // Skip processing if the SDK is present - it tracks opens automatically
        if (hasBundledSDK(processingRequest)) {
            return;
        }

        if (processingRequest.getEvents() != null) {
            List<PushMessageOpenEvent> pushOpenEvents = processingRequest.getEvents().stream()
                    .filter(e -> e.getType() == Event.Type.PUSH_MESSAGE_OPEN)
                    .map(e -> (PushMessageOpenEvent) e)
                    .collect(Collectors.toList());

            for (PushMessageOpenEvent event : pushOpenEvents) {
                TrackPushOpenRequest request = new TrackPushOpenRequest();
                if (event.getPayload() != null && processingRequest.getUserIdentities() != null) {
                    addUserIdentitiesToRequest(request, processingRequest);
                    if (request.email == null && request.userId == null) {
                        throw new IOException("Unable to process PushMessageOpenEvent - user has no email or customer id.");
                    }
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> payload = mapper.readValue(event.getPayload(), Map.class);
                    if (payload.containsKey("itbl")) {
                        //Android and iOS have differently encoded payload formats. See the tests for examples.
                        if (processingRequest.getRuntimeEnvironment() instanceof AndroidRuntimeEnvironment) {
                            Map<String, Object> iterableMap = mapper.readValue((String) payload.get("itbl"), Map.class);
                            request.campaignId = Integer.parseInt(mapper.writeValueAsString(iterableMap.get("campaignId")));
                            request.templateId = Integer.parseInt(mapper.writeValueAsString(iterableMap.get("templateId")));
                            request.messageId = (String) iterableMap.get("messageId");
                        } else {
                            request.campaignId = Integer.parseInt(mapper.writeValueAsString(((Map) payload.get("itbl")).get("campaignId")));
                            request.templateId = Integer.parseInt(mapper.writeValueAsString(((Map) payload.get("itbl")).get("templateId")));
                            request.messageId = (String) ((Map) payload.get("itbl")).get("messageId");
                        }
                        request.createdAt = (int) (event.getTimestamp() / 1000.0);
                        Response<IterableApiResponse> response = iterableService.trackPushOpen(getApiKey(processingRequest), request).execute();
                        if (response.isSuccessful() && !response.body().isSuccess()) {
                            throw new IOException(response.body().toString());
                        } else if (!response.isSuccessful()) {
                            throw new IOException("Error sending push-open to Iterable: HTTP " + response.code());
                        }
                    }
                }
            }
        }
    }

    private static String getApiKey(Event event) {
        Account account = event.getRequest().getAccount();
        return account.getStringSetting(SETTING_API_KEY, true, null);
    }

    private static String getApiKey(EventProcessingRequest event) {
        Account account = event.getAccount();
        return account.getStringSetting(SETTING_API_KEY, true, null);
    }

    private static String getApiKey(AudienceMembershipChangeRequest event) {
        Account account = event.getAccount();
        return account.getStringSetting(SETTING_API_KEY, true, null);
    }

    /**
     * Verify that there's an email present, create a placeholder if not.
     *
     * @param request
     * @throws IOException
     */
    private void insertPlaceholderEmail(EventProcessingRequest request) throws IOException {
        long count = request.getUserIdentities() == null ? 0 : request.getUserIdentities().stream()
                .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                .count();
        if (count > 0) {
            return;
        }
        String placeholderEmail = getPlaceholderEmail(request);
        if (request.getUserIdentities() == null) {
            request.setUserIdentities(new ArrayList<>());
        }
        request.getUserIdentities().add(new UserIdentity(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, placeholderEmail));
    }

    @Override
    public void processPushSubscriptionEvent(PushSubscriptionEvent event) throws IOException {
        // Skip processing if the SDK is present - it registers tokens automatically
        if (hasBundledSDK(event.getRequest())) {
            return;
        }

        RegisterDeviceTokenRequest request = new RegisterDeviceTokenRequest();
        if (PushSubscriptionEvent.Action.UNSUBSCRIBE.equals(event.getAction())) {
            return;
        }
        request.device = new Device();
        if (event.getRequest().getRuntimeEnvironment().getType().equals(RuntimeEnvironment.Type.IOS)) {
            Boolean sandboxed = ((IosRuntimeEnvironment) event.getRequest().getRuntimeEnvironment()).getIsSandboxed();
            if (sandboxed != null && sandboxed) {
                request.device.platform = Device.PLATFORM_APNS_SANDBOX;
                request.device.applicationName = event.getRequest().getAccount().getAccountSettings().get(SETTING_APNS_SANDBOX_KEY);
            } else {
                request.device.platform = Device.PLATFORM_APNS;
                request.device.applicationName = event.getRequest().getAccount().getAccountSettings().get(SETTING_APNS_KEY);
            }
        } else if (event.getRequest().getRuntimeEnvironment().getType().equals(RuntimeEnvironment.Type.ANDROID)) {
            request.device.platform = Device.PLATFORM_GCM;
            request.device.applicationName = event.getRequest().getAccount().getAccountSettings().get(SETTING_GCM_NAME_KEY);
        } else {
            throw new IOException("Cannot process push subscription event for unknown RuntimeEnvironment type.");
        }

        request.device.token = event.getToken();

        try {
            UserIdentity email = event.getRequest().getUserIdentities().stream()
                    .filter(t -> t.getType().equals(UserIdentity.Type.EMAIL))
                    .findFirst()
                    .get();
            request.email = email.getValue();
        } catch (NoSuchElementException e) {
            throw new IOException("Unable to construct Iterable RegisterDeviceTokenRequest - no user email.");
        }

        Response<IterableApiResponse> response = iterableService.registerToken(getApiKey(event), request).execute();
        if (response.isSuccessful() && !response.body().isSuccess()) {
            throw new IOException(response.body().toString());
        } else if (!response.isSuccessful()) {
            throw new IOException("Error sending push subscription to Iterable: " + response.body().toString());
        }
    }

    void updateUser(EventProcessingRequest request) throws IOException {
        if (request.getEvents() != null) {

            List<UserIdentityChangeEvent> emailChangeEvents = request.getEvents().stream()
                    .filter(e -> e.getType() == Event.Type.USER_IDENTITY_CHANGE)
                    .map(e -> (UserIdentityChangeEvent) e)
                    .filter(e -> e.getAdded() != null && e.getRemoved() != null)
                    .filter(e -> e.getAdded().size() > 0 && e.getRemoved().size() > 0)
                    .filter(e -> e.getAdded().get(0).getType().equals(UserIdentity.Type.EMAIL))
                    .filter(e -> !isEmpty(e.getAdded().get(0).getValue()) && !isEmpty(e.getRemoved().get(0).getValue()))
                    .collect(Collectors.toList());

            List<UserIdentityChangeEvent> emailAddedEvents = request.getEvents().stream()
                    .filter(e -> e.getType() == Event.Type.USER_IDENTITY_CHANGE)
                    .map(e -> (UserIdentityChangeEvent) e)
                    .filter(e -> e.getAdded() != null && e.getAdded().size() > 0)
                    .filter(e -> e.getRemoved() == null || e.getRemoved().size() == 0)
                    .filter(e -> e.getAdded().get(0).getType().equals(UserIdentity.Type.EMAIL))
                    .filter(e -> !isEmpty(e.getAdded().get(0).getValue()))
                    .collect(Collectors.toList());

            String placeholderEmail = getPlaceholderEmail(request);
            //convert from placeholder to email now that we have one
            for (UserIdentityChangeEvent changeEvent : emailAddedEvents) {
                UpdateEmailRequest updateEmailRequest = new UpdateEmailRequest();
                updateEmailRequest.currentEmail = placeholderEmail;
                //this is safe due to the filters above
                updateEmailRequest.newEmail = changeEvent.getAdded().get(0).getValue();
                Response<IterableApiResponse> response = iterableService.updateEmail(getApiKey(request), updateEmailRequest).execute();
                if (response.isSuccessful()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateEmail() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }

            //convert from old to new email
            for (UserIdentityChangeEvent changeEvent : emailChangeEvents) {
                UpdateEmailRequest updateEmailRequest = new UpdateEmailRequest();
                //these are safe due to the filters above
                updateEmailRequest.currentEmail = changeEvent.getRemoved().get(0).getValue();
                updateEmailRequest.newEmail = changeEvent.getAdded().get(0).getValue();
                Response<IterableApiResponse> response = iterableService.updateEmail(getApiKey(request), updateEmailRequest).execute();
                if (response.isSuccessful()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateEmail() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }
        }

        UserUpdateRequest userUpdateRequest = new UserUpdateRequest();
        if (request.getUserIdentities() != null) {
            addUserIdentitiesToRequest(userUpdateRequest, request);
            if (!isEmpty(userUpdateRequest.email) || !isEmpty(userUpdateRequest.userId)) {
                userUpdateRequest.dataFields = convertAttributes(request.getUserAttributes(), shouldCoerceStrings(request));
                Response<IterableApiResponse> response = iterableService.userUpdate(getApiKey(request), userUpdateRequest).execute();
                if (response.isSuccessful()) {
                    IterableApiResponse apiResponse = response.body();
                    if (apiResponse != null && !apiResponse.isSuccess()) {
                        throw new IOException("Error while calling updateUser() on iterable: HTTP " + apiResponse.code);
                    }
                }
            }
        }
    }

    private Map<String, Object> convertAttributes(Map<String, String> attributes, boolean coerceStringsToScalars) {
        if (attributes == null) {
            return null;
        }
        
        if (coerceStringsToScalars) {
            return attemptTypeConversion(attributes);
        } else {
            Map<String, Object> mapObj = new HashMap<String, Object>();
            mapObj.putAll(attributes);

            return mapObj;
        }
    }

    private static boolean shouldCoerceStrings(EventProcessingRequest request) {
        String settingValue = request.getAccount().getAccountSettings().get(SETTING_COERCE_STRINGS_TO_SCALARS);
        
        if (settingValue == null) { 
            return false;
        }
        return Boolean.parseBoolean(settingValue);
    }

    private static boolean shouldUseMPID(Account account) {
        String settingValue = account.getStringSetting(SETTING_USER_ID_FIELD,false, USER_ID_FIELD_CUSTOMER_ID);
        return settingValue.equals(USER_ID_FIELD_MPID);
    }

    private static boolean isEmpty(CharSequence chars) {
        return chars == null || "".equals(chars);
    }

    @Override
    public void processProductActionEvent(ProductActionEvent event) throws IOException {
        if (event.getAction().equals(ProductActionEvent.Action.PURCHASE)) {
            TrackPurchaseRequest purchaseRequest = new TrackPurchaseRequest();
            purchaseRequest.createdAt = (int) (event.getTimestamp() / 1000.0);
            ApiUser apiUser = new ApiUser();
            addUserIdentitiesToRequest(apiUser, event.getRequest());
            boolean shouldCoerceStrings = shouldCoerceStrings(event.getRequest());
            apiUser.dataFields = convertAttributes(event.getRequest().getUserAttributes(), shouldCoerceStrings);
            purchaseRequest.user = apiUser;
            purchaseRequest.total = event.getTotalAmount();
            if (event.getProducts() != null) {
                purchaseRequest.items = event.getProducts().stream()
                        .map(p -> convertToCommerceItem(p, shouldCoerceStrings))
                        .collect(Collectors.toList());
            }

            Response<IterableApiResponse> response = iterableService.trackPurchase(getApiKey(event), purchaseRequest).execute();
            if (response.isSuccessful() && !response.body().isSuccess()) {
                throw new IOException(response.body().toString());
            } else if (!response.isSuccessful()) {
                throw new IOException("Error sending custom event to Iterable: HTTP " + response.code());
            }
        }
    }

    CommerceItem convertToCommerceItem(Product product, boolean shouldCoerceStrings) {
        CommerceItem item = new CommerceItem();
        item.dataFields = convertAttributes(product.getAttributes(), shouldCoerceStrings);
        //iterable requires ID, and they also take SKU. mParticle doesn't differentiate
        //between sku and id. so, use our sku/id for both in Iterable:
        item.id = item.sku = product.getId();
        item.name = product.getName();
        item.price = product.getPrice();
        if (product.getQuantity() != null) {
            item.quantity = product.getQuantity().intValue();
        }
        if (product.getCategory() != null) {
            item.categories = new LinkedList<>();
            item.categories.add(product.getCategory());
        }
        return item;
    }

    @Override
    public void processUserAttributeChangeEvent(UserAttributeChangeEvent event) throws IOException {
        //there's no reason to do this - it's already done at the start of batch processing
        //updateUser(event.getContext());
    }

    /**
     *
     * Make the best attempt at creating a placeholder email, prioritize:
     *  1. MPID if enabled
     *  2. Platform respective device IDs
     *  3. customer Id
     *  4. device application stamps
     * @param request
     * @return
     *
     * Also see: https://support.iterable.com/hc/en-us/articles/208499956-Creating-user-profiles-without-an-email-address
     */
    static String getPlaceholderEmail(EventProcessingRequest request) throws IOException {
        String id = null;
        if (shouldUseMPID(request.getAccount())) {
            id = request.getMpId();
        } else {
            if (request.getRuntimeEnvironment() instanceof IosRuntimeEnvironment || request.getRuntimeEnvironment() instanceof TVOSRuntimeEnvironment ) {
                List<DeviceIdentity> deviceIdentities = null;
                if (request.getRuntimeEnvironment() instanceof IosRuntimeEnvironment) {
                    deviceIdentities = ((IosRuntimeEnvironment) request.getRuntimeEnvironment()).getIdentities();
                } else {
                    deviceIdentities = ((TVOSRuntimeEnvironment) request.getRuntimeEnvironment()).getIdentities();
                }
                if (deviceIdentities != null) {
                    DeviceIdentity deviceIdentity = deviceIdentities.stream().filter(t -> t.getType().equals(DeviceIdentity.Type.IOS_VENDOR_ID))
                            .findFirst()
                            .orElse(null);
                    if (deviceIdentity != null) {
                        id = deviceIdentity.getValue();
                    }
                    if (isEmpty(id)) {
                        deviceIdentity = deviceIdentities.stream().filter(t -> t.getType().equals(DeviceIdentity.Type.IOS_ADVERTISING_ID))
                                .findFirst()
                                .orElse(null);
                        if (deviceIdentity != null) {
                            id = deviceIdentity.getValue();
                        }
                    }
                }
            } else if (request.getRuntimeEnvironment() instanceof AndroidRuntimeEnvironment) {
                if (((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities() != null) {
                    DeviceIdentity deviceIdentity = ((AndroidRuntimeEnvironment)request.getRuntimeEnvironment()).getIdentities().stream().filter(t -> t.getType().equals(DeviceIdentity.Type.GOOGLE_ADVERTISING_ID))
                            .findFirst()
                            .orElse(null);
                    if (deviceIdentity != null) {
                        id = deviceIdentity.getValue();
                    }
                    if (isEmpty(id)) {
                        deviceIdentity = ((AndroidRuntimeEnvironment) request.getRuntimeEnvironment()).getIdentities().stream().filter(t -> t.getType().equals(DeviceIdentity.Type.ANDROID_ID))
                                .findFirst()
                                .orElse(null);
                        if (deviceIdentity != null) {
                            id = deviceIdentity.getValue();
                        }
                    }
                }
            }

            if (isEmpty(id)) {
                if (request.getUserIdentities() != null) {
                    UserIdentity customerId = request.getUserIdentities().stream()
                            .filter(t -> t.getType().equals(UserIdentity.Type.CUSTOMER))
                            .findFirst()
                            .orElse(null);
                    if (customerId != null) {
                        id = customerId.getValue();
                    }
                }
            }

            if (isEmpty(id)) {
                id = request.getDeviceApplicationStamp();
            }
        }

        if (isEmpty(id)) {
            throw new IOException("Unable to send user to Iterable - no email and unable to construct placeholder.");
        }
        return id + "@placeholder.email";
    }

    @Override
    public ModuleRegistrationResponse processRegistrationRequest(ModuleRegistrationRequest request) {
        ModuleRegistrationResponse response = new ModuleRegistrationResponse(NAME, "1.6.0");

        Permissions permissions = new Permissions();
        permissions.setUserIdentities(
                Arrays.asList(
                        new UserIdentityPermission(UserIdentity.Type.EMAIL, Identity.Encoding.RAW, false),
                        new UserIdentityPermission(UserIdentity.Type.CUSTOMER, Identity.Encoding.RAW, false)
                )
        );
        permissions.setDeviceIdentities(
                Arrays.asList(
                        new DeviceIdentityPermission(DeviceIdentity.Type.GOOGLE_CLOUD_MESSAGING_TOKEN, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.APPLE_PUSH_NOTIFICATION_TOKEN, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.IOS_VENDOR_ID, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.ANDROID_ID, Identity.Encoding.RAW),
                        new DeviceIdentityPermission(DeviceIdentity.Type.GOOGLE_ADVERTISING_ID, Identity.Encoding.RAW)
                )
        );
        permissions.setAllowAccessDeviceApplicationStamp(true);
        permissions.setAllowUserAttributes(true);
        permissions.setAllowDeviceInformation(true);
        permissions.setAllowAccessMpid(true);
        response.setPermissions(permissions);
        response.setDescription("<a href=\"https://www.iterable.com\">Iterable</a> makes consumer growth marketing and user engagement simple. With Iterable, marketers send the right message, to the right device, at the right time.");
        EventProcessingRegistration eventProcessingRegistration = new EventProcessingRegistration()
                .setSupportedRuntimeEnvironments(
                        Arrays.asList(
                                RuntimeEnvironment.Type.ANDROID,
                                RuntimeEnvironment.Type.IOS,
                                RuntimeEnvironment.Type.MOBILEWEB,
                                RuntimeEnvironment.Type.UNKNOWN)
                );
        eventProcessingRegistration.setPushMessagingProviderId("itbl");

        List<Setting> eventSettings = new ArrayList<>();
        List<Setting> audienceSettings = new ArrayList<>();
        Setting apiKey = new TextSetting(SETTING_API_KEY, "API Key")
                .setIsRequired(true)
                .setIsConfidential(true)
                .setDescription("API key used to connect to the Iterable API - see the Integrations section of your Iterable account.");
        Setting userIdField = new TextSetting(SETTING_USER_ID_FIELD, "User ID")
                .setIsRequired(true)
                .setDefaultValue(USER_ID_FIELD_CUSTOMER_ID)
                .setDescription("Select which user identity to forward to Iterable as your customer's user ID.");

        audienceSettings.add(apiKey);
        audienceSettings.add(userIdField);

        eventSettings.add(apiKey);
        eventSettings.add(
                new TextSetting(SETTING_GCM_NAME_KEY, "GCM Push Integration Name")
                        .setIsRequired(false)
                        .setDescription("GCM integration name set up in the Mobile Push section of your Iterable account.")

        );
        eventSettings.add(
                new TextSetting(SETTING_APNS_SANDBOX_KEY, "APNS Sandbox Integration Name")
                        .setIsRequired(false)
                        .setDescription("APNS Sandbox integration name set up in the Mobile Push section of your Iterable account.")
        );
        eventSettings.add(
                new TextSetting(SETTING_APNS_KEY, "APNS Production Integration Name")
                        .setIsRequired(false)
                        .setDescription("APNS Production integration name set up in the Mobile Push section of your Iterable account.")
        );
        eventSettings.add(
                new BooleanSetting(SETTING_COERCE_STRINGS_TO_SCALARS, "Coerce Strings to Scalars")
                        .setIsChecked(true)
                        .setDescription("If enabled, mParticle will attempt to coerce string attributes into scalar types (integer, boolean, and float).")
        );
        eventProcessingRegistration.setAccountSettings(eventSettings);

        List<Setting> connectionSettings = new ArrayList<>();
        connectionSettings.add(userIdField);
        eventProcessingRegistration.setConnectionSettings(connectionSettings);

        // Specify supported event types
        List<Event.Type> supportedEventTypes = Arrays.asList(
                Event.Type.CUSTOM_EVENT,
                Event.Type.PUSH_SUBSCRIPTION,
                Event.Type.PUSH_MESSAGE_RECEIPT,
                Event.Type.PUSH_MESSAGE_OPEN,
                Event.Type.USER_IDENTITY_CHANGE,
                Event.Type.PRODUCT_ACTION);

        eventProcessingRegistration.setSupportedEventTypes(supportedEventTypes);
        response.setEventProcessingRegistration(eventProcessingRegistration);
        AudienceProcessingRegistration audienceRegistration = new AudienceProcessingRegistration();
        audienceRegistration.setAccountSettings(audienceSettings);
        List<Setting> subscriptionSettings = new LinkedList<>();
        IntegerSetting listIdSetting = new IntegerSetting(SETTING_LIST_ID, "List ID");
        listIdSetting.setIsRequired(true);
        listIdSetting.setDescription("The ID of the Iterable list to populate with the users from this segment.");
        subscriptionSettings.add(listIdSetting);
        audienceRegistration.setAudienceConnectionSettings(subscriptionSettings);
        response.setAudienceProcessingRegistration(audienceRegistration);

        return response;
    }

    private static List<Integer> convertToIntList(String csv){
        if (csv == null) {
            return null;
        } else if (csv.isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> list = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(csv, ",");
        while (st.hasMoreTokens()) {
            list.add(Integer.parseInt(st.nextToken().trim()));
        }
        return list;
    }

    public static final String UPDATE_SUBSCRIPTIONS_CUSTOM_EVENT_NAME = "subscriptionsUpdated";
    public static final String EMAIL_LIST_ID_LIST_KEY = "emailListIds";
    public static final String UNSUBSCRIBE_CHANNEL_ID_LIST_KEY = "unsubscribedChannelIds";
    public static final String UNSUBSCRIBE_MESSAGE_TYPE_ID_LIST_KEY = "unsubscribedMessageTypeIds";
    public static final String CAMPAIGN_ID_KEY = "campaignId";
    public static final String TEMPLATE_ID_KEY = "templateId";

    /**
     *
     * This is expected to be called with an event that conforms to the following:
     * Name: "updateSubscriptions"
     *
     * And has at least some of the following:
     *
     * Attribute: emailListIds
     * Attribute: unsubscribedChannelIds
     * Attribute: unsubscribedMessageTypeIds
     * Attribute: campaignId
     * Attribute: templateId
     *
     */
    private boolean processSubscribeEvent(CustomEvent event) throws IOException {
        UpdateSubscriptionsRequest updateRequest = generateSubscriptionRequest(event);
        if (updateRequest == null) {
            return false;
        }
        Response<IterableApiResponse> response = iterableService.updateSubscriptions(getApiKey(event), updateRequest).execute();
        if (response.isSuccessful() && !response.body().isSuccess()) {
            throw new IOException(response.body().toString());
        } else if (!response.isSuccessful()) {
            throw new IOException("Error sending update subscriptions event to Iterable: HTTP " + response.code());
        }
        return true;

    }

    static UpdateSubscriptionsRequest generateSubscriptionRequest(CustomEvent event) {
        if (!UPDATE_SUBSCRIPTIONS_CUSTOM_EVENT_NAME.equalsIgnoreCase(event.getName())) {
            return null;
        }
        UpdateSubscriptionsRequest updateRequest = new UpdateSubscriptionsRequest();

        Map<String, String> eventAttributes = event.getAttributes();
        updateRequest.emailListIds = convertToIntList(eventAttributes.get(EMAIL_LIST_ID_LIST_KEY));
        updateRequest.unsubscribedChannelIds = convertToIntList(eventAttributes.get(UNSUBSCRIBE_CHANNEL_ID_LIST_KEY));
        updateRequest.unsubscribedMessageTypeIds = convertToIntList(eventAttributes.get(UNSUBSCRIBE_MESSAGE_TYPE_ID_LIST_KEY));

        String campaignId = eventAttributes.get(CAMPAIGN_ID_KEY);
        if (!isEmpty(campaignId)) {
            try {
                updateRequest.campaignId = Integer.parseInt(campaignId.trim());
            }catch (NumberFormatException ignored) {

            }
        }

        String templateId = eventAttributes.get(TEMPLATE_ID_KEY);
        if (!isEmpty(templateId)) {
            try {
                updateRequest.templateId = Integer.parseInt(templateId.trim());
            }catch (NumberFormatException ignored) {

            }
        }

        List<UserIdentity> identities = event.getRequest().getUserIdentities();
        if (identities != null) {
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    updateRequest.email = identity.getValue();
                }
            }
        }
        return updateRequest;
    }

    @Override
    public void processCustomEvent(CustomEvent event) throws IOException {
        if (processSubscribeEvent(event)) {
            return;
        }

        TrackRequest request = new TrackRequest(event.getName());
        request.createdAt = (int) (event.getTimestamp() / 1000.0);
        request.dataFields = attemptTypeConversion(event.getAttributes());
        addUserIdentitiesToRequest(request, event.getRequest());

        Response<IterableApiResponse> response = iterableService.track(getApiKey(event), request).execute();
        if (response.isSuccessful() && !response.body().isSuccess()) {
            throw new IOException(response.body().toString());
        } else if (!response.isSuccessful()) {
            throw new IOException("Error sending custom event to Iterable: HTTP " + response.code());
        }
    }

    /**
     * Make a best-effort attempt to coerce the values of each map item to bool, double, int, and string types
     *
     * mParticle's API only accepts string, whereas Iterable's API accept different types. By coercing these types,
     * users of the Iterable API are able to create campaigns, aggregate events, etc.
     *
     * @param attributes
     * @return
     */
    private Map<String, Object> attemptTypeConversion(Map<String, String> attributes) {
        if (attributes == null) {
            return null;
        }
        Map<String, Object> converted = new HashMap<>(attributes.size());
        attributes.forEach((key,value)-> {
            if (isEmpty(value)) {
                converted.put(key, value);
            } else {
                if (value.toLowerCase(Locale.US).equals("true") || value.toLowerCase(Locale.US).equals("false")) {
                    converted.put(key, Boolean.parseBoolean(value));
                } else {
                    try {
                        double doubleValue = Double.parseDouble(value);
                        if ((doubleValue % 1) == 0) {
                            converted.put(key, Integer.parseInt(value));
                        } else {
                            converted.put(key, doubleValue);
                        }
                    }catch (NumberFormatException nfe) {
                        converted.put(key, value);
                    }
                }
            }

        });
        return converted;
    }

    @Override
    public void processPushMessageReceiptEvent(PushMessageReceiptEvent event) throws IOException {
        // Skip processing if the SDK is present - it tracks opens automatically
        if (hasBundledSDK(event.getRequest())) {
            return;
        }

        TrackPushOpenRequest request = new TrackPushOpenRequest();
        if (event.getPayload() != null && event.getRequest().getUserIdentities() != null) {
            addUserIdentitiesToRequest(request, event.getRequest());
            if (request.email == null && request.userId == null) {
                throw new IOException("Unable to process PushMessageReceiptEvent - user has no email or customer id.");
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> payload = mapper.readValue(event.getPayload(), Map.class);
            if (payload.containsKey("itbl")) {
                //Android and iOS have differently encoded payload formats. See the tests for examples.
                if (event.getRequest().getRuntimeEnvironment() instanceof AndroidRuntimeEnvironment) {
                    Map<String, Object> iterableMap = mapper.readValue((String) payload.get("itbl"), Map.class);
                    request.campaignId = Integer.parseInt(mapper.writeValueAsString(iterableMap.get("campaignId")));
                    request.templateId = Integer.parseInt(mapper.writeValueAsString(iterableMap.get("templateId")));
                    request.messageId = (String) iterableMap.get("messageId");
                } else {
                    request.campaignId = Integer.parseInt(mapper.writeValueAsString(((Map) payload.get("itbl")).get("campaignId")));
                    request.templateId = Integer.parseInt(mapper.writeValueAsString(((Map) payload.get("itbl")).get("templateId")));
                    request.messageId = (String) ((Map) payload.get("itbl")).get("messageId");
                }
                request.createdAt = (int) (event.getTimestamp() / 1000.0);
                Response<IterableApiResponse> response = iterableService.trackPushOpen(getApiKey(event), request).execute();
                if (response.isSuccessful() && !response.body().isSuccess()) {
                    throw new IOException(response.body().toString());
                } else if (!response.isSuccessful()) {
                    throw new IOException("Error sending push-open to Iterable: HTTP " + response.code());
                }
            }
        }
    }

    /**
     * Map an AudienceMembershipChangeRequest to Iterable's list subscribe and unsubscribe requests.
     *
     * Requests are made to the /api/lists/subscribe and /api/lists/unsubscribe endpoint. Each request
     * will contain multiple users if there are multiple users being added or removed from the same
     * list. No dataFields are sent with the users.
     *
     * https://api.iterable.com/api/docs#lists_subscribe
     * https://api.iterable.com/api/docs#lists_unsubscribe
     *
     * @param request the request
     * @return a response that indicates the request was processed successfully
     * @throws IOException
     */
    @Override
    public AudienceMembershipChangeResponse processAudienceMembershipChangeRequest(AudienceMembershipChangeRequest request) throws IOException {
        HashMap<Integer, List<ApiUser>> additions = new HashMap<>();
        HashMap<Integer, List<ApiUser>> removals = new HashMap<>();
        for (UserProfile profile : request.getUserProfiles()) {
            UserRequest userRequest = new UserRequest() {};
            addUserIdentitiesToRequest(userRequest, profile, request.getAccount());
            if (userRequest.email != null) {
                if (profile.getAudiences() != null) {
                    List<Audience> addedAudiences = profile.getAudiences().stream()
                            .filter(audience -> audience.getAudienceAction() == Audience.AudienceAction.ADD)
                            .collect(Collectors.toList());
                    List<Audience> removedAudiences = profile.getAudiences().stream()
                            .filter(audience -> audience.getAudienceAction() == Audience.AudienceAction.DELETE)
                            .collect(Collectors.toList());
                    for (Audience audience : addedAudiences) {
                        Map<String, String> audienceSettings = audience.getAudienceSubscriptionSettings();
                        int listId = Integer.parseInt(audienceSettings.get(SETTING_LIST_ID));
                        ApiUser user = new ApiUser();
                        user.email = userRequest.email;
                        user.userId = userRequest.userId;
                        if (!additions.containsKey(listId)) {
                            additions.put(listId, new LinkedList<>());
                        }
                        additions.get(listId).add(user);
                    }
                    for (Audience audience : removedAudiences) {
                        Map<String, String> audienceSettings = audience.getAudienceSubscriptionSettings();
                        int listId = Integer.parseInt(audienceSettings.get(SETTING_LIST_ID));
                        ApiUser user = new ApiUser();
                        user.email = userRequest.email;
                        user.userId = userRequest.userId;
                        if (!removals.containsKey(listId)) {
                            removals.put(listId, new LinkedList<>());
                        }
                        removals.get(listId).add(user);
                    }
                }
            }
        }

        for (Map.Entry<Integer, List<ApiUser>> entry : additions.entrySet()) {
            SubscribeRequest subscribeRequest = new SubscribeRequest();
            subscribeRequest.listId = entry.getKey();
            subscribeRequest.subscribers = entry.getValue();
            try {
                Response<ListResponse> response = iterableService.listSubscribe(getApiKey(request), subscribeRequest).execute();
                if (response.isSuccessful()) {
                    ListResponse listResponse = response.body();
                    if (listResponse.failCount > 0) {
                        throw new IOException("Iterable list subscribe had positive fail count: " + listResponse.failCount);
                    }
                } else if (!response.isSuccessful()) {
                    throw new IOException("Error sending list subscribe to Iterable: HTTP " + response.code());
                }
            } catch (Exception e) {

            }
        }

        for (Map.Entry<Integer, List<ApiUser>> entry : removals.entrySet()) {
            UnsubscribeRequest unsubscribeRequest = new UnsubscribeRequest();
            unsubscribeRequest.listId = entry.getKey();
            unsubscribeRequest.subscribers = entry.getValue();
            try {
                Response<ListResponse> response = iterableService.listUnsubscribe(getApiKey(request), unsubscribeRequest).execute();
                if (response.isSuccessful()) {
                    ListResponse listResponse = response.body();
                    if (listResponse.failCount > 0) {
                        throw new IOException("Iterable list unsubscribe had positive fail count: " + listResponse.failCount);
                    }
                } else if (!response.isSuccessful()) {
                    throw new IOException("Error sending list unsubscribe to Iterable: HTTP " + response.code());
                }
            } catch (Exception e) {

            }
        }
        return new AudienceMembershipChangeResponse();
    }

    private void addUserIdentitiesToRequest(UserRequest request, EventProcessingRequest processingRequest) {
        addUserIdentitiesToRequest(request, processingRequest.getUserIdentities(), processingRequest.getAccount(), processingRequest.getMpId());
    }

    private void addUserIdentitiesToRequest(UserRequest request, UserProfile userProfile, Account account) {
        addUserIdentitiesToRequest(request, userProfile.getUserIdentities(), account, userProfile.getMpId());
    }

    private void addUserIdentitiesToRequest(UserRequest request, List<UserIdentity> identities, Account account, String mpid) {
        if (identities != null) {
            for (UserIdentity identity : identities) {
                if (identity.getType().equals(UserIdentity.Type.EMAIL)) {
                    request.email = identity.getValue();
                } else if (identity.getType().equals(UserIdentity.Type.CUSTOMER) && !shouldUseMPID(account)) {
                    request.userId = identity.getValue();
                }
            }
        }
        if (shouldUseMPID(account)) {
            request.userId = mpid;
            if (request.email == null && mpid != null) {
                request.email = mpid + "@placeholder.email";
            }
        }
    }

    private boolean hasBundledSDK(EventProcessingRequest processingRequest) {
        Map<String, String> integrationAttributes = processingRequest.getIntegrationAttributes();
        return integrationAttributes != null &&
                integrationAttributes.getOrDefault("Iterable.sdkVersion", null) != null;
    }

}
