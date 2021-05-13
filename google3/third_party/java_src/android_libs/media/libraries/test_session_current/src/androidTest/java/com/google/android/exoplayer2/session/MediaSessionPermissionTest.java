/*
 * Copyright 2018 The Android Open Source Project
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

package com.google.android.exoplayer2.session;

import static com.google.android.exoplayer2.Player.COMMAND_ADJUST_DEVICE_VOLUME;
import static com.google.android.exoplayer2.Player.COMMAND_CHANGE_MEDIA_ITEMS;
import static com.google.android.exoplayer2.Player.COMMAND_PLAY_PAUSE;
import static com.google.android.exoplayer2.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM;
import static com.google.android.exoplayer2.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static com.google.android.exoplayer2.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
import static com.google.android.exoplayer2.Player.COMMAND_SET_DEVICE_VOLUME;
import static com.google.android.exoplayer2.Player.COMMAND_SET_MEDIA_ITEMS_METADATA;
import static com.google.android.exoplayer2.session.MediaUtils.createPlayerCommandsWith;
import static com.google.android.exoplayer2.session.MediaUtils.createPlayerCommandsWithout;
import static com.google.android.exoplayer2.session.SessionCommand.COMMAND_CODE_SESSION_SET_MEDIA_URI;
import static com.google.android.exoplayer2.session.SessionCommand.COMMAND_CODE_SESSION_SET_RATING;
import static com.google.android.exoplayer2.session.SessionResult.RESULT_SUCCESS;
import static com.google.android.exoplayer2.session.vct.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static com.google.android.exoplayer2.session.vct.common.TestUtils.NO_RESPONSE_TIMEOUT_MS;
import static com.google.android.exoplayer2.session.vct.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import com.google.android.exoplayer2.MediaMetadata;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Rating;
import com.google.android.exoplayer2.StarRating;
import com.google.android.exoplayer2.session.MediaSession.ControllerInfo;
import com.google.android.exoplayer2.session.vct.common.HandlerThreadTestRule;
import com.google.android.exoplayer2.session.vct.common.MainLooperTestRule;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for permission handling of {@link MediaSession}. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MediaSessionPermissionTest {
  private static final String SESSION_ID = "MediaSessionTest_permission";

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule
  public final HandlerThreadTestRule threadTestRule =
      new HandlerThreadTestRule("MediaSessionPermissionTest");

  @Rule public final RemoteControllerTestRule controllerTestRule = new RemoteControllerTestRule();

  private Context context;
  private MockPlayer player;
  private MediaSession session;
  private MySessionCallback callback;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @After
  public void cleanUp() {
    if (session != null) {
      session.release();
      session = null;
    }
    player = null;
    callback = null;
  }

  private void createSessionWithAvailableCommands(
      SessionCommands sessionCommands, Player.Commands playerCommands) {
    player =
        new MockPlayer.Builder()
            .setLatchCount(1)
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .build();
    callback =
        new MySessionCallback() {
          @Nullable
          @Override
          public MediaSession.ConnectResult onConnect(
              MediaSession session, ControllerInfo controller) {
            if (!TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controller.getPackageName())) {
              return null;
            }
            return new MediaSession.ConnectResult(sessionCommands, playerCommands);
          }
        };
    if (this.session != null) {
      this.session.release();
    }
    this.session =
        new MediaSession.Builder(context, player)
            .setId(SESSION_ID)
            .setSessionCallback(callback)
            .build();
  }

  private SessionCommands createSessionCommandsWith(SessionCommand command) {
    return new SessionCommands.Builder().add(command).build();
  }

  private void testOnCommandRequest(int commandCode, PermissionTestTask runnable) throws Exception {
    createSessionWithAvailableCommands(
        SessionCommands.EMPTY, createPlayerCommandsWith(commandCode));
    runnable.run(controllerTestRule.createRemoteController(session.getToken()));

    assertThat(callback.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(callback.onCommandRequestCalled).isTrue();
    assertThat(callback.command).isEqualTo(commandCode);

    createSessionWithAvailableCommands(
        SessionCommands.EMPTY, createPlayerCommandsWithout(commandCode));
    runnable.run(controllerTestRule.createRemoteController(session.getToken()));

    assertThat(callback.countDownLatch.await(NO_RESPONSE_TIMEOUT_MS, MILLISECONDS)).isFalse();
    assertThat(callback.onCommandRequestCalled).isFalse();
  }

  @Test
  public void play() throws Exception {
    testOnCommandRequest(COMMAND_PLAY_PAUSE, RemoteMediaController::play);
  }

  @Test
  public void pause() throws Exception {
    testOnCommandRequest(COMMAND_PLAY_PAUSE, RemoteMediaController::pause);
  }

  @Test
  public void seekTo() throws Exception {
    long position = 10;
    testOnCommandRequest(
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, controller -> controller.seekTo(position));
  }

  @Test
  public void seekToNextMediaItem() throws Exception {
    testOnCommandRequest(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, RemoteMediaController::next);
  }

  @Test
  public void seekToPreviousMediaItem() throws Exception {
    testOnCommandRequest(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, RemoteMediaController::previous);
  }

  @Test
  public void setPlaylistMetadata() throws Exception {
    testOnCommandRequest(
        COMMAND_SET_MEDIA_ITEMS_METADATA,
        controller -> controller.setPlaylistMetadata(MediaMetadata.EMPTY));
  }

  @Test
  public void setMediaItems() throws Exception {
    testOnCommandRequest(
        COMMAND_CHANGE_MEDIA_ITEMS,
        controller -> controller.setMediaItems(Collections.emptyList()));
  }

  @Test
  public void addMediaItems() throws Exception {
    testOnCommandRequest(
        COMMAND_CHANGE_MEDIA_ITEMS,
        controller -> controller.addMediaItems(/* index= */ 0, Collections.emptyList()));
  }

  @Test
  public void removeMediaItems() throws Exception {
    testOnCommandRequest(
        COMMAND_CHANGE_MEDIA_ITEMS,
        controller -> controller.removeMediaItems(/* fromIndex= */ 0, /* toIndex= */ 1));
  }

  @Test
  public void setDeviceVolume() throws Exception {
    testOnCommandRequest(COMMAND_SET_DEVICE_VOLUME, controller -> controller.setDeviceVolume(0));
  }

  @Test
  public void increaseDeviceVolume() throws Exception {
    testOnCommandRequest(COMMAND_ADJUST_DEVICE_VOLUME, RemoteMediaController::increaseDeviceVolume);
  }

  @Test
  public void decreaseDeviceVolume() throws Exception {
    testOnCommandRequest(COMMAND_ADJUST_DEVICE_VOLUME, RemoteMediaController::decreaseDeviceVolume);
  }

  @Test
  public void setDeviceMuted() throws Exception {
    testOnCommandRequest(COMMAND_SET_DEVICE_VOLUME, controller -> controller.setDeviceMuted(true));
  }

  @Test
  public void setMediaUri() throws Exception {
    Uri uri = Uri.parse("media://uri");
    createSessionWithAvailableCommands(
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_MEDIA_URI)),
        Player.Commands.EMPTY);
    controllerTestRule
        .createRemoteController(session.getToken())
        .setMediaUri(uri, /* extras= */ null);

    assertThat(callback.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(callback.onSetMediaUriCalled).isTrue();
    assertThat(callback.uri).isEqualTo(uri);
    assertThat(callback.extras).isNull();

    createSessionWithAvailableCommands(
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_RATING)),
        Player.Commands.EMPTY);
    controllerTestRule
        .createRemoteController(session.getToken())
        .setMediaUri(uri, /* extras= */ null);
    assertThat(callback.countDownLatch.await(NO_RESPONSE_TIMEOUT_MS, MILLISECONDS)).isFalse();
    assertThat(callback.onSetMediaUriCalled).isFalse();
  }

  @Test
  public void setRating() throws Exception {
    String mediaId = "testSetRating";
    Rating rating = new StarRating(5, 3.5f);
    createSessionWithAvailableCommands(
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_RATING)),
        Player.Commands.EMPTY);
    controllerTestRule.createRemoteController(session.getToken()).setRating(mediaId, rating);

    assertThat(callback.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(callback.onSetRatingCalled).isTrue();
    assertThat(callback.mediaId).isEqualTo(mediaId);
    assertThat(callback.rating).isEqualTo(rating);

    createSessionWithAvailableCommands(
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_MEDIA_URI)),
        Player.Commands.EMPTY);
    controllerTestRule.createRemoteController(session.getToken()).setRating(mediaId, rating);
    assertThat(callback.countDownLatch.await(NO_RESPONSE_TIMEOUT_MS, MILLISECONDS)).isFalse();
    assertThat(callback.onSetRatingCalled).isFalse();
  }

  @Test
  public void changingPermissionForSessionCommandWithSetAvailableCommands() throws Exception {
    String mediaId = "testSetRating";
    Rating rating = new StarRating(5, 3.5f);
    createSessionWithAvailableCommands(
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_RATING)),
        Player.Commands.EMPTY);
    RemoteMediaController controller =
        controllerTestRule.createRemoteController(session.getToken());

    controller.setRating(mediaId, rating);
    assertThat(callback.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(callback.onSetRatingCalled).isTrue();
    callback.reset();

    // Change allowed commands.
    session.setAvailableCommands(
        getTestControllerInfo(),
        createSessionCommandsWith(new SessionCommand(COMMAND_CODE_SESSION_SET_MEDIA_URI)),
        Player.Commands.EMPTY);

    controller.setRating(mediaId, rating);
    assertThat(callback.countDownLatch.await(NO_RESPONSE_TIMEOUT_MS, MILLISECONDS)).isFalse();
  }

  @Test
  public void changingPermissionForPlayerCommandWithSetAvailableCommands() throws Exception {
    int playPauseCommand = COMMAND_PLAY_PAUSE;
    Player.Commands commandsWithPlayPause = createPlayerCommandsWith(playPauseCommand);
    Player.Commands commandsWithoutPlayPause = createPlayerCommandsWithout(playPauseCommand);

    // Create session with play/pause command.
    createSessionWithAvailableCommands(SessionCommands.EMPTY, commandsWithPlayPause);
    // Create player with play/pause command.
    player.commands = commandsWithPlayPause;
    player.notifyAvailableCommandsChanged(commandsWithPlayPause);
    RemoteMediaController controller =
        controllerTestRule.createRemoteController(session.getToken());

    controller.play();
    assertThat(callback.countDownLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(callback.onCommandRequestCalled).isTrue();
    assertThat(callback.command).isEqualTo(playPauseCommand);
    callback.reset();

    // Change session to not have play/pause command.
    session.setAvailableCommands(
        getTestControllerInfo(), SessionCommands.EMPTY, commandsWithoutPlayPause);

    controller.play();
    assertThat(callback.countDownLatch.await(NO_RESPONSE_TIMEOUT_MS, MILLISECONDS)).isFalse();
    assertThat(callback.onCommandRequestCalled).isFalse();
  }

  private ControllerInfo getTestControllerInfo() {
    List<ControllerInfo> controllers = session.getConnectedControllers();
    assertThat(controllers).isNotNull();
    for (int i = 0; i < controllers.size(); i++) {
      if (TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controllers.get(i).getPackageName())) {
        return controllers.get(i);
      }
    }
    throw new IllegalStateException("Failed to get test controller info");
  }

  /* @FunctionalInterface */
  private interface PermissionTestTask {
    void run(@NonNull RemoteMediaController controller) throws Exception;
  }

  private static class MySessionCallback extends MediaSession.SessionCallback {
    public CountDownLatch countDownLatch;

    public @Player.Command int command;
    public String mediaId;
    public Uri uri;
    public Bundle extras;
    public Rating rating;

    public boolean onCommandRequestCalled;
    public boolean onSetMediaUriCalled;
    public boolean onSetRatingCalled;

    public MySessionCallback() {
      countDownLatch = new CountDownLatch(1);
    }

    public void reset() {
      countDownLatch = new CountDownLatch(1);

      mediaId = null;

      onCommandRequestCalled = false;
      onSetMediaUriCalled = false;
      onSetRatingCalled = false;
    }

    @Override
    public int onPlayerCommandRequest(
        @NonNull MediaSession session,
        @NonNull ControllerInfo controller,
        @Player.Command int command) {
      assertThat(TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controller.getPackageName())).isTrue();
      onCommandRequestCalled = true;
      this.command = command;
      countDownLatch.countDown();
      return super.onPlayerCommandRequest(session, controller, command);
    }

    @Override
    public int onSetMediaUri(
        @NonNull MediaSession session,
        @NonNull ControllerInfo controller,
        @NonNull Uri uri,
        @Nullable Bundle extras) {
      assertThat(TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controller.getPackageName())).isTrue();
      onSetMediaUriCalled = true;
      this.uri = uri;
      this.extras = extras;
      countDownLatch.countDown();
      return RESULT_SUCCESS;
    }

    @Override
    @NonNull
    public ListenableFuture<SessionResult> onSetRating(
        @NonNull MediaSession session,
        @NonNull ControllerInfo controller,
        @NonNull String mediaId,
        @NonNull Rating rating) {
      assertThat(TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controller.getPackageName())).isTrue();
      onSetRatingCalled = true;
      this.mediaId = mediaId;
      this.rating = rating;
      countDownLatch.countDown();
      return new SessionResult(RESULT_SUCCESS).asFuture();
    }
  }
}