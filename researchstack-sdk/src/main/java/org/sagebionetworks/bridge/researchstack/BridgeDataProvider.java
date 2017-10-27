package org.sagebionetworks.bridge.researchstack;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.joda.time.LocalDate;
import org.researchstack.backbone.DataProvider;
import org.researchstack.backbone.DataResponse;
import org.researchstack.backbone.ResourceManager;
import org.researchstack.backbone.StorageAccess;
import org.researchstack.backbone.model.ConsentSignatureBody;
import org.researchstack.backbone.model.SchedulesAndTasksModel;
import org.researchstack.backbone.model.User;
import org.researchstack.backbone.result.StepResult;
import org.researchstack.backbone.result.TaskResult;
import org.researchstack.backbone.storage.NotificationHelper;
import org.researchstack.backbone.task.Task;
import org.researchstack.backbone.ui.ActiveTaskActivity;
import org.researchstack.backbone.ui.step.layout.ConsentSignatureStepLayout;
import org.researchstack.backbone.utils.ObservableUtils;
import org.researchstack.skin.AppPrefs;
import org.researchstack.skin.model.TaskModel;
import org.researchstack.skin.task.ConsentTask;
import org.sagebionetworks.bridge.android.BridgeConfig;
import org.sagebionetworks.bridge.android.manager.AuthenticationManager;
import org.sagebionetworks.bridge.android.manager.BridgeManagerProvider;
import org.sagebionetworks.bridge.researchstack.wrapper.StorageAccessWrapper;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.model.ConsentSignature;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignUp;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.functions.Action0;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.researchstack.ApiUtils.SUCCESS_DATA_RESPONSE;

/**
 * DataProvider implementation backed by a Bridge study.
 */
public abstract class BridgeDataProvider extends DataProvider {
    private static final Logger logger = LoggerFactory.getLogger(BridgeDataProvider.class);

    // set in initialize
    private final TaskHelper taskHelper;

    @NonNull
    private final StorageAccessWrapper storageAccessWrapper;
    @NonNull
    private final ResearchStackDAO researchStackDAO;
    @NonNull
    private final BridgeManagerProvider bridgeManagerProvider;
    @NonNull
    private final BridgeConfig bridgeConfig;
    @NonNull
    private final AuthenticationManager authenticationManager;

    //used by tests to mock service
    BridgeDataProvider(ResearchStackDAO researchStackDAO, StorageAccessWrapper storageAccessWrapper,
                       TaskHelper taskHelper) {
        this.researchStackDAO = researchStackDAO;
        this.storageAccessWrapper = storageAccessWrapper;
        this.taskHelper = taskHelper;

        this.bridgeManagerProvider = BridgeManagerProvider.getInstance();

        // convenience accessors
        this.bridgeConfig = bridgeManagerProvider.getBridgeConfig();
        this.authenticationManager = bridgeManagerProvider.getAuthenticationManager();
    }

    public BridgeDataProvider(@NonNull BridgeManagerProvider bridgeManagerProvider) {
        this.researchStackDAO = new ResearchStackDAO(bridgeManagerProvider.getApplicationContext());
        this.bridgeManagerProvider = bridgeManagerProvider;
        // convenience accessors
        this.bridgeConfig = bridgeManagerProvider.getBridgeConfig();
        this.authenticationManager = bridgeManagerProvider.getAuthenticationManager();

        this.storageAccessWrapper = new StorageAccessWrapper();

        NotificationHelper notificationHelper = NotificationHelper.
                getInstance(bridgeManagerProvider.getApplicationContext());
        this.taskHelper = createTaskHelper(notificationHelper, storageAccessWrapper,
                bridgeManagerProvider);
    }

    public TaskHelper createTaskHelper(NotificationHelper notif, StorageAccessWrapper wrapper,
                                       BridgeManagerProvider provider) {
        return new TaskHelper(wrapper, ResourceManager.getInstance(), AppPrefs.getInstance(),
                notif, provider);
    }

    @Override
    public Observable<DataResponse> initialize(Context context) {
        logger.debug("Called initialize");

        return SUCCESS_DATA_RESPONSE;
    }

    @NonNull
    @Override
    public String getStudyId() {
        return bridgeConfig.getStudyId();
    }

    //region Consent

    @NonNull
    @Override
    public Observable<DataResponse> withdrawConsent(Context context, String reason) {
        logger.debug("Called withdrawConsent");
        //TODO: allow withdrawal from specific subpopulation

        return withdrawAllConsents(reason).andThen(SUCCESS_DATA_RESPONSE);
    }

    @NonNull
    public Completable withdrawConsent(@NonNull String subpopulationGuid, @Nullable String reason) {
        return authenticationManager.withdrawConsent(subpopulationGuid, reason);
    }


    @NonNull
    public Completable withdrawAllConsents(@Nullable String reason) {
        return authenticationManager.withdrawAll(reason);
    }

    /**
     * @return true if participant has consented to all required consents
     */
    @Override
    public boolean isConsented() {
        logger.debug("Called isConsented");
        return authenticationManager.isConsented();
    }

    @NonNull
    public Single<UserSessionInfo> giveConsent(@NonNull String subpopulationGuid, @NonNull String
            name,
                                               @NonNull LocalDate birthdate,
                                               @Nullable String base64Image, @Nullable String
                                                       imageMimeType,
                                               @NonNull SharingScope sharingScope) {
        return authenticationManager.giveConsent(subpopulationGuid, name, birthdate, base64Image,
                imageMimeType, sharingScope);
    }

    @NonNull
    public Single<UserSessionInfo> giveConsent(@NonNull String subpopulationGuid, @NonNull
            ConsentSignature consentSignature) {
        return giveConsent(subpopulationGuid,
                consentSignature.getName(),
                consentSignature.getBirthdate(),
                consentSignature.getImageData(),
                consentSignature.getImageMimeType(),
                consentSignature.getScope());
    }

    @NonNull
    public Single<ConsentSignature> getConsent(@NonNull String subpopulation) {
        checkNotNull(subpopulation);

        return authenticationManager.getConsentSignature(subpopulation);
    }

    // TODO: getConsent rid of Consent methods below on the interface. let ConsentManager handle the
    // implementation details and expose leave giveConsent, getConsent, withdrawConsent, and
    // isConsented
    @Nullable
    @Override
    public ConsentSignatureBody loadLocalConsent(Context context) {
        ConsentSignatureBody consent = createConsentSignatureBody(
                authenticationManager.getConsentSync(bridgeConfig.getStudyId()));
        logger.debug("loadLocalConsent called, got: ");
        return consent;
    }

    @Override
    public void saveConsent(Context context, @NonNull TaskResult consentResult) {
        logger.warn("saveConsent called -- DEPRECATED");
        giveConsent(bridgeConfig.getStudyId(), createConsentSignature(consentResult))
                .toBlocking().value();
    }

    @Override
    public void saveLocalConsent(Context context, ConsentSignatureBody signatureBody) {
        logger.debug("saveLocalConsent called for: " + signatureBody);
        LocalDate birthdate = null;
        if (signatureBody.birthdate != null) {
            birthdate = LocalDate.fromDateFields(signatureBody.birthdate);
        }
        authenticationManager.giveConsentSync(bridgeConfig.getStudyId(),
                signatureBody.name,
                birthdate,
                signatureBody.imageData,
                signatureBody.imageMimeType,
                toSharingScope(signatureBody.scope));
    }

    private SharingScope toSharingScope(String sharingScope) {
        SharingScope scopeEnum = SharingScope.NO_SHARING;
        for (SharingScope scope : SharingScope.values()) {
            if (scope.toString().equals(sharingScope)) {
                scopeEnum = scope;
            }
        }
        return scopeEnum;
    }

    @Nullable
    protected ConsentSignature createConsentSignature(@Nullable ConsentSignatureBody
                                                              consentSignatureBody) {
        if (consentSignatureBody == null) {
            return null;
        }
        ConsentSignature signature = new ConsentSignature();
        signature.setName(consentSignatureBody.name);
        if (consentSignatureBody.birthdate != null) {
            signature.setBirthdate(LocalDate.fromDateFields(consentSignatureBody.birthdate));
        }
        SharingScope sharingScope = toSharingScope(consentSignatureBody.scope);
        signature.setImageData(consentSignatureBody.imageData);
        signature.setImageMimeType(consentSignatureBody.imageMimeType);
        signature.setScope(sharingScope);
        return signature;
    }

    @Nullable
    protected ConsentSignatureBody createConsentSignatureBody(@Nullable ConsentSignature
                                                                      consentSignature) {
        if (consentSignature == null) {
            return null;
        }

        return new ConsentSignatureBody(
                getStudyId(),
                consentSignature.getName(),
                consentSignature.getBirthdate() != null ? consentSignature.getBirthdate().toDate
                        () : null,
                consentSignature.getImageData(),
                consentSignature.getImageMimeType(),
                consentSignature.getScope() != null ? consentSignature.getScope().toString() :
                        null);
    }

    @NonNull
    protected ConsentSignature createConsentSignature(@NonNull TaskResult consentResult) {
        StepResult<StepResult> formResult =
                (StepResult<StepResult>) consentResult.getStepResult(ConsentTask.ID_FORM);

        String sharingScope = (String) consentResult.getStepResult(ConsentTask.ID_SHARING)
                .getResult();

        String fullName =
                (String) formResult.getResultForIdentifier(ConsentTask.ID_FORM_NAME).getResult();

        Long birthdateInMillis =
                (Long) formResult.getResultForIdentifier(ConsentTask.ID_FORM_DOB).getResult();

        String base64Image = (String) consentResult.getStepResult(ConsentTask.ID_SIGNATURE)
                .getResultForIdentifier(ConsentSignatureStepLayout.KEY_SIGNATURE);

        String signatureDate = (String) consentResult.getStepResult(ConsentTask.ID_SIGNATURE)
                .getResultForIdentifier(ConsentSignatureStepLayout.KEY_SIGNATURE_DATE);

        // Save Consent Information
        // User is not signed in yet, so we need to save consent info to disk for later upload
        return new ConsentSignature()
                .name(fullName)
                .birthdate(new LocalDate(birthdateInMillis))
                .imageData(base64Image)
                .imageMimeType("image/png")
                .scope(SharingScope.valueOf(sharingScope));
    }

    @Override
    public void uploadConsent(Context context, @NonNull TaskResult consentResult) {
        giveConsentSync(createConsentSignature(consentResult));
        uploadConsent(bridgeConfig.getStudyId(),
                createConsentSignature(consentResult));
    }

    private void giveConsentSync(@NonNull ConsentSignature consentSignature) {
        authenticationManager.giveConsentSync(bridgeConfig.getStudyId(),
                consentSignature.getName(),
                consentSignature.getBirthdate(),
                consentSignature.getImageData(),
                consentSignature.getImageMimeType(),
                consentSignature.getScope());
    }

    @Override
    public Observable<DataResponse> uploadConsent(Context context, ConsentSignatureBody signature) {
        return uploadConsent(bridgeConfig.getStudyId(), createConsentSignature(signature));
    }

    private Observable<DataResponse> uploadConsent(@NonNull String subpopulationGuid, @NonNull
            ConsentSignature
            consent) {
        return giveConsent(
                subpopulationGuid,
                consent.getName(),
                consent.getBirthdate(),
                consent.getImageData(),
                consent.getImageMimeType(),
                consent.getScope())
                .flatMapObservable(session -> SUCCESS_DATA_RESPONSE)
                .compose(ObservableUtils.applyDefault());
    }

    //endregion

    //region Account

    @NonNull
    @Override
    public Observable<DataResponse> signUp(Context context, String email, String username,
                                           String password) {
        logger.debug("Called signUp");
        // we should pass in data groups, removeConsent roles
        SignUp signUp = new SignUp().study(getStudyId()).email(email).password(password);
        return signUp(signUp);
    }

    @NonNull
    public Observable<DataResponse> signUp(@NonNull SignUp signUp) {
        // saving email to user object should exist elsewhere.
        // Save email to user object.

        return signUp(signUp.getEmail(), signUp.getPassword());
    }

    @NonNull
    public Observable<DataResponse> signUp(@NonNull String email, @NonNull String password) {
        checkNotNull(email);
        checkNotNull(password);

        return authenticationManager
                .signUp(email, password)
                .andThen(SUCCESS_DATA_RESPONSE);
    }

    @Override
    public boolean isSignedUp(@Nullable Context context) {

        return authenticationManager.getEmail() != null;
    }

    public boolean isSignedUp() {
        return authenticationManager.getEmail() != null;
    }

    @Override
    @NonNull
    public Observable<DataResponse> signIn(@Nullable Context context, @NonNull String username,
                                           @NonNull String password) {
        logger.debug("Called signIn");

        return signIn(username, password)
                .andThen(SUCCESS_DATA_RESPONSE);
    }

    /**
     * @param email    the participant's email
     * @param password participant's password
     * @return completion
     * @see DataProvider#signIn(Context, String, String)
     * <p>
     * May fail with ConsentRequiredException, to indicate
     * consent is required.
     * NotAuthenticatedException could indicate the user has not verified their email
     */
    @NonNull
    public Completable signIn(@NonNull String email, @NonNull String password) {
        checkNotNull(email);
        checkNotNull(password);

        return authenticationManager
                .signIn(email, password)
                .toCompletable().doOnCompleted((Action0) () -> {
                    // TODO: upload pending files
                });
    }

    public boolean isSignedIn() {
        return authenticationManager.getEmail() != null;
    }

    @Deprecated
    @Override
    public boolean isSignedIn(Context context) {
        return isSignedIn();
    }


    @Override
    public Observable<DataResponse> signOut(Context context) {
        logger.debug("Called signOut");

        return signOut().doOnCompleted(new Action0() {
            @Override
            public void call() {
                // After the call is completed and successful,
                // Clear the other parts of the user data
                AppPrefs.getInstance().clear();
                StorageAccess.getInstance().removePinCode(context);
            }
        }).andThen(SUCCESS_DATA_RESPONSE);
    }

    @NonNull
    public Completable signOut() {
        return authenticationManager.signOut();
    }

    @NonNull
    @Override
    public Observable<DataResponse> resendEmailVerification(Context context, @NonNull String
            email) {
        return resendEmailVerification(email).andThen(SUCCESS_DATA_RESPONSE);
    }

    @NonNull
    public Completable resendEmailVerification(@NonNull String email) {
        checkNotNull(email);

        return authenticationManager.resendEmailVerification(email);
    }

    /**
     * Called to verify the user's email address
     * Behind the scenes this calls signIn with securely stored username and password
     *
     * @param context android context
     * @return Observable of the result of the method, with {@link DataResponse#isSuccess()}
     * returning true if verifyEmail was successful
     */
    @NonNull
    public Observable<DataResponse> verifyEmail(@Nullable Context context, @NonNull String
            password) {
        return verifyEmail(getUserEmail(context), password).andThen(SUCCESS_DATA_RESPONSE);
    }

    @NonNull
    public Completable verifyEmail(@NonNull String email, @NonNull String password) {
        checkNotNull(email);
        checkNotNull(password);

        return authenticationManager.signIn(email, password).toCompletable();
    }

    @NonNull
    @Override
    public Observable<DataResponse> forgotPassword(Context context, @NonNull String email) {
        return forgotPassword(email).andThen(SUCCESS_DATA_RESPONSE);
    }

    @NonNull
    public Completable forgotPassword(@NonNull String email) {
        checkNotNull(email);

        return authenticationManager
                .requestPasswordReset(email);
    }

    //endregion

    //region User

    @Override
    @Nullable
    public User getUser(@Nullable Context context) {
        return researchStackDAO.getUser();
    }

    @Override
    @Nullable
    public void setUser(Context context, User user) {
        researchStackDAO.setUser(user);
    }

    @Nullable
    @Override
    public String getUserEmail(Context context) {
        return authenticationManager.getEmail();
    }

    //endregion

    //region SharingScope

    @Override
    @Nullable
    public String getUserSharingScope(Context context) {
        logger.debug("Called getUserSharingScope");

        SharingScope scope = getUserSharingScope();
        return scope == null ? null : scope.toString();
    }

    @Nullable
    public SharingScope getUserSharingScope() {
        UserSessionInfo session = authenticationManager.getUserSessionInfo();
        if (session == null) {
            return null;
        }
        return session.getSharingScope();
    }


    @Override
    public void setUserSharingScope(Context context, String scope) {
        StudyParticipant participant = new StudyParticipant();
        SharingScope sharingScope = RestUtils.GSON.fromJson(scope, SharingScope.class);
        participant.setSharingScope(sharingScope);

        setUserSharingScope(sharingScope)
                .subscribe();
    }

    @NonNull
    public Single<UserSessionInfo> setUserSharingScope(@Nullable SharingScope scope) {

        return bridgeManagerProvider.getParticipantManager()
                .updateParticipantRecord((StudyParticipant) new StudyParticipant()
                        .email(authenticationManager.getEmail())
                        .sharingScope(scope));
    }

    //endregion

    //region TasksAndSchedules

    @NonNull
    @Override
    public SchedulesAndTasksModel loadTasksAndSchedules(Context context) {
        logger.info("loadTasksAndSchedules()");

        // TODO: figure out the correct arguments to pass here
        ScheduledActivityList scheduledActivityList = bridgeManagerProvider.getActivityManager()
                .getActivities(4, 0)
                .toBlocking()
                .value();

        SchedulesAndTasksModel model = translateActivities(scheduledActivityList);

        return model;
    }

    private TaskModel loadTaskModel(Context context, SchedulesAndTasksModel.TaskScheduleModel
            task) {

        // cache guid and createdOnDate

        return taskHelper.loadTaskModel(context, task);
    }

    @Override
    public Task loadTask(Context context, SchedulesAndTasksModel.TaskScheduleModel task) {
        // currently we only support task json files, override this method to taskClassName

        return taskHelper.loadTask(context, task);
    }

    @Override
    public void uploadTaskResult(Context context, @NonNull TaskResult taskResult) {
        // TODO: Update/Create TaskNotificationService

        boolean isActivity = false;
        if (taskResult.getTaskDetails().containsKey(ActiveTaskActivity.ACTIVITY_TASK_RESULT_KEY)) {
            Object isActivityObject = taskResult.getTaskDetails().get(ActiveTaskActivity
                    .ACTIVITY_TASK_RESULT_KEY);
            if (isActivityObject instanceof Boolean) {
                isActivity = (Boolean) isActivityObject;
            }
        }

        if (isActivity) {
            taskHelper.uploadActivityResult(taskResult.getIdentifier(), taskResult);
        } else {
            taskHelper.uploadSurveyResult(taskResult);
        }
    }

    @Override
    public abstract void processInitialTaskResult(Context context, TaskResult taskResult);
    //endregion

    //
    // NOTE: this is a crude translation and needs to be updated to properly
    //       handle schedules and filters
    @NonNull
    private SchedulesAndTasksModel translateActivities(@NonNull ScheduledActivityList
                                                               activityList) {
        logger.info("translateActivities()");

        // first, group activities by day
        Map<Integer, List<ScheduledActivity>> activityMap = new HashMap<>();
        for (ScheduledActivity sa : activityList.getItems()) {
            int day = sa.getScheduledOn().dayOfYear().get();
            List<ScheduledActivity> actList = activityMap.get(day);
            if (actList == null) {
                actList = new ArrayList<>();
                actList.add(sa);
                activityMap.put(day, actList);
            } else {
                actList.add(sa);
            }
        }

        SchedulesAndTasksModel model = new SchedulesAndTasksModel();
        model.schedules = new ArrayList<>();
        for (int day : activityMap.keySet()) {
            List<ScheduledActivity> aList = activityMap.get(day);
            ScheduledActivity temp = aList.get(0);

            SchedulesAndTasksModel.ScheduleModel sm = new SchedulesAndTasksModel.ScheduleModel();
            sm.scheduleType = "once";
            sm.scheduledOn = temp.getScheduledOn().toDate();
            model.schedules.add(sm);
            sm.tasks = new ArrayList<>();

            for (ScheduledActivity sa : aList) {
                SchedulesAndTasksModel.TaskScheduleModel tsm = new SchedulesAndTasksModel
                        .TaskScheduleModel();
                tsm.taskTitle = sa.getActivity().getLabel();
                tsm.taskCompletionTime = sa.getActivity().getLabelDetail();
                if (sa.getActivity().getTask() != null) {
                    tsm.taskID = sa.getActivity().getTask().getIdentifier();
                }
                tsm.taskIsOptional = sa.getPersistent();
                tsm.taskType = sa.getActivity().getType();
                sm.tasks.add(tsm);
            }
        }

        return model;
    }
}
