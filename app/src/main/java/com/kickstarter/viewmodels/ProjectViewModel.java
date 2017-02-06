package com.kickstarter.viewmodels;

import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.kickstarter.libs.ActivityViewModel;
import com.kickstarter.libs.Config;
import com.kickstarter.libs.CurrentConfigType;
import com.kickstarter.libs.CurrentUserType;
import com.kickstarter.libs.Environment;
import com.kickstarter.libs.RefTag;
import com.kickstarter.libs.utils.RefTagUtils;
import com.kickstarter.models.Project;
import com.kickstarter.models.User;
import com.kickstarter.services.ApiClientType;
import com.kickstarter.services.apiresponses.PushNotificationEnvelope;
import com.kickstarter.ui.activities.ProjectActivity;
import com.kickstarter.ui.adapters.ProjectAdapter;
import com.kickstarter.ui.intentmappers.IntentMapper;
import com.kickstarter.ui.intentmappers.ProjectIntentMapper;
import com.kickstarter.ui.viewholders.ProjectViewHolder;
import com.kickstarter.viewmodels.inputs.ProjectViewModelInputs;
import com.kickstarter.viewmodels.outputs.ProjectViewModelOutputs;

import java.net.CookieManager;

import rx.Observable;
import rx.subjects.BehaviorSubject;
import rx.subjects.PublishSubject;

import static com.kickstarter.libs.rx.transformers.Transformers.combineLatestPair;
import static com.kickstarter.libs.rx.transformers.Transformers.neverError;
import static com.kickstarter.libs.rx.transformers.Transformers.takeWhen;

public final class ProjectViewModel extends ActivityViewModel<ProjectActivity> implements ProjectAdapter.Delegate,
  ProjectViewModelInputs, ProjectViewModelOutputs {
  private final ApiClientType client;
  private final CurrentUserType currentUser;
  private final CookieManager cookieManager;
  private final CurrentConfigType currentConfig;
  private final SharedPreferences sharedPreferences;

  /**
   * A light-weight value to hold two ref tags and a project. Two ref tags are stored: one comes from parceled
   * data in the activity and the other comes from the ref stored in a cookie associated to the project.
   */
  private final class RefTagsAndProject {
    private final @Nullable RefTag refTagFromIntent;
    private final @Nullable RefTag refTagFromCookie;
    private final @NonNull Project project;

    private RefTagsAndProject(final @Nullable RefTag refTagFromIntent, final @Nullable RefTag refTagFromCookie,
      final @NonNull Project project) {
      this.refTagFromIntent = refTagFromIntent;
      this.refTagFromCookie = refTagFromCookie;
      this.project = project;
    }

    public @NonNull Project project() {
      return project;
    }
  }
  
  public ProjectViewModel(final @NonNull Environment environment) {
    super(environment);

    client = environment.apiClient();
    cookieManager = environment.cookieManager();
    currentConfig = environment.currentConfig();
    currentUser = environment.currentUser();
    sharedPreferences = environment.sharedPreferences();

    // An observable of the ref tag stored in the cookie for the project. Can emit `null`.
    final Observable<RefTag> cookieRefTag = project
      .take(1)
      .map(p -> RefTagUtils.storedCookieRefTagForProject(p, cookieManager, sharedPreferences));

    final Observable<Project> initialProject = intent()
      .flatMap(i -> ProjectIntentMapper.project(i, client))
      .share();

    final Observable<RefTag> refTag = intent()
      .flatMap(ProjectIntentMapper::refTag);

    final Observable<PushNotificationEnvelope> pushNotificationEnvelope = intent()
      .flatMap(ProjectIntentMapper::pushNotificationEnvelope);

    final Observable<User> loggedInUserOnStarClick = currentUser.observable()
      .compose(takeWhen(starClicked))
      .filter(u -> u != null);

    final Observable<User> loggedOutUserOnStarClick = currentUser.observable()
      .compose(takeWhen(starClicked))
      .filter(u -> u == null);

    final Observable<Project> projectOnUserChangeStar = initialProject
      .compose(takeWhen(loggedInUserOnStarClick))
      .switchMap(this::toggleProjectStar)
      .share();

    final Observable<Project> starredProjectOnLoginSuccess = showLoginTout
      .compose(combineLatestPair(currentUser.observable()))
      .filter(su -> su.second != null)
      .withLatestFrom(initialProject, (__, p) -> p)
      .take(1)
      .switchMap(this::starProject)
      .share();

    initialProject
      .mergeWith(projectOnUserChangeStar)
      .mergeWith(starredProjectOnLoginSuccess)
      .compose(bindToLifecycle())
      .subscribe(this.project::onNext);

    projectOnUserChangeStar.mergeWith(starredProjectOnLoginSuccess)
      .filter(Project::isStarred)
      .filter(Project::isLive)
      .filter(p -> !p.isApproachingDeadline())
      .compose(bindToLifecycle())
      .subscribe(__ -> this.showStarredPrompt.onNext(null));

    loggedOutUserOnStarClick
      .compose(bindToLifecycle())
      .subscribe(__ -> this.showLoginTout.onNext(null));

    shareClicked
      .compose(bindToLifecycle())
      .subscribe(__ -> koala.trackShowProjectShareSheet());

    playVideoClicked
      .compose(bindToLifecycle())
      .subscribe(__ -> koala.trackVideoStart(project.getValue()));

    projectOnUserChangeStar
      .mergeWith(starredProjectOnLoginSuccess)
      .compose(bindToLifecycle())
      .subscribe(koala::trackProjectStar);

    Observable.combineLatest(refTag, cookieRefTag, project, RefTagsAndProject::new)
      .take(1)
      .compose(bindToLifecycle())
      .subscribe(data -> {
        // If a cookie hasn't been set for this ref+project then do so.
        if (data.refTagFromCookie == null && data.refTagFromIntent != null) {
          RefTagUtils.storeCookie(data.refTagFromIntent, data.project, cookieManager, sharedPreferences);
        }

        koala.trackProjectShow(
          data.project,
          data.refTagFromIntent,
          RefTagUtils.storedCookieRefTagForProject(data.project, cookieManager, sharedPreferences)
        );
      });

    pushNotificationEnvelope
      .take(1)
      .compose(bindToLifecycle())
      .subscribe(koala::trackPushNotification);

    intent()
      .filter(IntentMapper::appBannerIsSet)
      .compose(bindToLifecycle())
      .subscribe(__ -> koala.trackOpenedAppBanner());
  }

  public @NonNull Observable<Project> starProject(final @NonNull Project project) {
    return client.starProject(project)
      .compose(neverError());
  }

  public @NonNull Observable<Project> toggleProjectStar(final @NonNull Project project) {
    return client.toggleProjectStar(project)
      .compose(neverError());
  }

  private final PublishSubject<Void> backProjectClicked = PublishSubject.create();
  private final PublishSubject<Void> shareClicked = PublishSubject.create();
  private final PublishSubject<Void> blurbClicked = PublishSubject.create();
  private final PublishSubject<Void> commentsClicked = PublishSubject.create();
  private final PublishSubject<Void> creatorNameClicked = PublishSubject.create();
  private final PublishSubject<Void> managePledgeClicked = PublishSubject.create();
  private final PublishSubject<Void> updatesClicked = PublishSubject.create();
  private final PublishSubject<Void> playVideoClicked = PublishSubject.create();
  private final PublishSubject<Void> viewPledgeClicked = PublishSubject.create();
  private final PublishSubject<Void> starClicked = PublishSubject.create();

  private final BehaviorSubject<Project> project = BehaviorSubject.create();
  private final PublishSubject<Void> showLoginTout = PublishSubject.create();
  private final PublishSubject<Void> showStarredPrompt = PublishSubject.create();

  public final ProjectViewModelInputs inputs = this;
  public final ProjectViewModelOutputs outputs = this;
  
  public void backProjectClicked() {
    this.backProjectClicked.onNext(null);
  }
  public void blurbClicked() {
    this.blurbClicked.onNext(null);
  }
  public void commentsClicked() {
    this.commentsClicked.onNext(null);
  }
  public void creatorNameClicked() {
    this.creatorNameClicked.onNext(null);
  }
  public void managePledgeClicked() {
    this.managePledgeClicked.onNext(null);
  }
  public void playVideoClicked() {
    this.playVideoClicked.onNext(null);
  }
  public void projectViewHolderBackProjectClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.backProjectClicked();
  }
  public void projectViewHolderBlurbClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.blurbClicked();
  }
  public void projectViewHolderCommentsClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.commentsClicked();
  }
  public void projectViewHolderCreatorClicked(final @NonNull ProjectViewHolder viewHolder){
    this.creatorNameClicked();
  }
  public void projectViewHolderManagePledgeClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.managePledgeClicked();
  }
  public void projectViewHolderVideoStarted(final @NonNull ProjectViewHolder viewHolder) {
    this.playVideoClicked();
  }
  public void projectViewHolderViewPledgeClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.viewPledgeClicked();
  }
  public void projectViewHolderUpdatesClicked(final @NonNull ProjectViewHolder viewHolder) {
    this.updatesClicked();
  }
  public void shareClicked() {
    this.shareClicked.onNext(null);
  }
  public void starClicked() {
    this.starClicked.onNext(null);
  }
  public void updatesClicked() {
    this.updatesClicked.onNext(null);
  }
  public void viewPledgeClicked() {
    this.viewPledgeClicked.onNext(null);
  }

  @Override public @NonNull Observable<Project> playVideo() {
    return this.project.compose(takeWhen(this.playVideoClicked));
  }
  @Override public @NonNull Observable<Pair<Project, String>> projectAndUserCountry() {
    return this.project.compose(combineLatestPair(this.currentConfig.observable().map(Config::countryCode)));
  }
  @Override public @NonNull Observable<Project> startCampaignWebViewActivity() {
    return this.project.compose(takeWhen(this.blurbClicked));
  }
  @Override public @NonNull Observable<Project> startCreatorBioWebViewActivity() {
    return this.project.compose(takeWhen(this.creatorNameClicked));
  }
  @Override public @NonNull Observable<Project> startCommentsActivity() {
    return this.project.compose(takeWhen(this.commentsClicked));
  }
  @Override public @NonNull Observable<Void> showLoginTout() {
    return this.showLoginTout;
  }
  @Override public @NonNull Observable<Project> showShareSheet() {
    return this.project.compose(takeWhen(this.shareClicked));
  }
  @Override public @NonNull Observable<Void> showStarredPrompt() {
    return this.showStarredPrompt;
  }
  @Override public @NonNull Observable<Project> startProjectUpdatesActivity() {
    return this.project.compose(takeWhen(this.updatesClicked));
  }
  @Override public @NonNull Observable<Project> startCheckout() {
    return this.project.compose(takeWhen(this.backProjectClicked));
  }
  @Override public @NonNull Observable<Project> startManagePledge() {
    return this.project.compose(takeWhen(this.managePledgeClicked));
  }
  @Override public @NonNull Observable<Project> startViewPledge() {
    return this.project.compose(takeWhen(this.viewPledgeClicked));
  }
}
