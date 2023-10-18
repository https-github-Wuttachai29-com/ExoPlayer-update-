/*
 * Copyright 2023 The Android Open Source Project
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
package com.google.android.exoplayer2.video;

import static com.google.android.exoplayer2.util.Assertions.checkArgument;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.DebugViewProvider;
import com.google.android.exoplayer2.util.Effect;
import com.google.android.exoplayer2.util.FrameInfo;
import com.google.android.exoplayer2.util.LongArrayQueue;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Size;
import com.google.android.exoplayer2.util.SurfaceInfo;
import com.google.android.exoplayer2.util.TimedValueQueue;
import com.google.android.exoplayer2.util.TimestampIterator;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.VideoFrameProcessingException;
import com.google.android.exoplayer2.util.VideoFrameProcessor;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Handles composition of video sinks.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class CompositingVideoSinkProvider implements VideoSinkProvider {

  private final Context context;
  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private final VideoFrameReleaseControl videoFrameReleaseControl;

  @Nullable private VideoSinkImpl videoSinkImpl;
  @Nullable private List<Effect> videoEffects;
  @Nullable private VideoFrameMetadataListener videoFrameMetadataListener;
  private boolean released;

  /** Creates a new instance. */
  public CompositingVideoSinkProvider(
      Context context,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      VideoFrameReleaseControl renderControl) {
    this(
        context,
        new ReflectivePreviewingSingleInputVideoGraphFactory(videoFrameProcessorFactory),
        renderControl);
  }

  @VisibleForTesting
  /* package */ CompositingVideoSinkProvider(
      Context context,
      PreviewingVideoGraph.Factory previewingVideoGraphFactory,
      VideoFrameReleaseControl releaseControl) {
    this.context = context;
    this.previewingVideoGraphFactory = previewingVideoGraphFactory;
    this.videoFrameReleaseControl = releaseControl;
  }

  @Override
  public void initialize(Format sourceFormat) throws VideoSink.VideoSinkException {
    checkState(!released && videoSinkImpl == null);
    checkStateNotNull(videoEffects);

    try {
      videoSinkImpl =
          new VideoSinkImpl(
              context, previewingVideoGraphFactory, videoFrameReleaseControl, sourceFormat);
    } catch (VideoFrameProcessingException e) {
      throw new VideoSink.VideoSinkException(e, sourceFormat);
    }

    if (videoFrameMetadataListener != null) {
      videoSinkImpl.setVideoFrameMetadataListener(videoFrameMetadataListener);
    }
    videoSinkImpl.setVideoEffects(checkNotNull(videoEffects));
  }

  @Override
  public boolean isInitialized() {
    return videoSinkImpl != null;
  }

  @Override
  public void release() {
    if (released) {
      return;
    }

    if (videoSinkImpl != null) {
      videoSinkImpl.release();
      videoSinkImpl = null;
    }
    released = true;
  }

  @Override
  public VideoSink getSink() {
    return checkStateNotNull(videoSinkImpl);
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    this.videoEffects = videoEffects;
    if (isInitialized()) {
      checkStateNotNull(videoSinkImpl).setVideoEffects(videoEffects);
    }
  }

  @Override
  public void setPendingVideoEffects(List<Effect> videoEffects) {
    this.videoEffects = videoEffects;
    if (isInitialized()) {
      checkStateNotNull(videoSinkImpl).setPendingVideoEffects(videoEffects);
    }
  }

  @Override
  public void setStreamOffsetUs(long streamOffsetUs) {
    checkStateNotNull(videoSinkImpl).setStreamOffsetUs(streamOffsetUs);
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    checkStateNotNull(videoSinkImpl).setOutputSurfaceInfo(outputSurface, outputResolution);
  }

  @Override
  public void clearOutputSurfaceInfo() {
    checkStateNotNull(videoSinkImpl).clearOutputSurfaceInfo();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
    if (isInitialized()) {
      checkStateNotNull(videoSinkImpl).setVideoFrameMetadataListener(videoFrameMetadataListener);
    }
  }

  private static final class VideoSinkImpl implements VideoSink, VideoGraph.Listener {

    private final Context context;
    private final VideoFrameReleaseControl videoFrameReleaseControl;
    private final VideoFrameReleaseControl.FrameReleaseInfo videoFrameReleaseInfo;
    private final VideoFrameProcessor videoFrameProcessor;
    private final LongArrayQueue processedFramesBufferTimestampsUs;
    private final TimedValueQueue<Long> streamOffsets;
    private final TimedValueQueue<VideoSize> videoSizeChanges;
    private final Handler handler;
    private final int videoFrameProcessorMaxPendingFrameCount;
    private final ArrayList<Effect> videoEffects;
    @Nullable private final Effect rotationEffect;

    private VideoSink.@MonotonicNonNull Listener listener;
    private @MonotonicNonNull Executor listenerExecutor;
    @Nullable private VideoFrameMetadataListener videoFrameMetadataListener;
    @Nullable private Format inputFormat;
    @Nullable private Pair<Surface, Size> currentSurfaceAndSize;

    /**
     * Whether the last frame of the current stream is decoded and registered to {@link
     * VideoFrameProcessor}.
     */
    private boolean registeredLastFrame;

    /**
     * Whether the last frame of the current stream is processed by the {@link VideoFrameProcessor}.
     */
    private boolean processedLastFrame;

    /** Whether the last frame of the current stream is released to the output {@link Surface}. */
    private boolean releasedLastFrame;

    private long lastCodecBufferPresentationTimestampUs;
    private VideoSize processedFrameSize;
    private VideoSize reportedVideoSize;
    private boolean pendingVideoSizeChange;
    private long inputStreamOffsetUs;
    private boolean pendingInputStreamOffsetChange;
    private long outputStreamOffsetUs;

    // TODO b/292111083 - Remove the field and trigger the callback on every video size change.
    private boolean onVideoSizeChangedCalled;

    /** Creates a new instance. */
    public VideoSinkImpl(
        Context context,
        PreviewingVideoGraph.Factory previewingVideoGraphFactory,
        VideoFrameReleaseControl videoFrameReleaseControl,
        Format sourceFormat)
        throws VideoFrameProcessingException {
      this.context = context;
      this.videoFrameReleaseControl = videoFrameReleaseControl;
      videoFrameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
      processedFramesBufferTimestampsUs = new LongArrayQueue();
      streamOffsets = new TimedValueQueue<>();
      videoSizeChanges = new TimedValueQueue<>();
      // TODO b/226330223 - Investigate increasing frame count when frame dropping is
      //  allowed.
      // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
      //  reduces decoder timeouts, and consider restoring.
      videoFrameProcessorMaxPendingFrameCount =
          Util.getMaxPendingFramesCountForMediaCodecDecoders(context);
      lastCodecBufferPresentationTimestampUs = C.TIME_UNSET;
      processedFrameSize = VideoSize.UNKNOWN;
      reportedVideoSize = VideoSize.UNKNOWN;

      // Playback thread handler.
      handler = Util.createHandlerForCurrentLooper();

      ColorInfo inputColorInfo =
          sourceFormat.colorInfo != null && ColorInfo.isTransferHdr(sourceFormat.colorInfo)
              ? sourceFormat.colorInfo
              : ColorInfo.SDR_BT709_LIMITED;
      ColorInfo outputColorInfo = inputColorInfo;
      if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) {
        // SurfaceView only supports BT2020 PQ input. Therefore, convert HLG to PQ.
        outputColorInfo =
            inputColorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build();
      }

      @SuppressWarnings("nullness:assignment")
      @Initialized
      VideoSinkImpl thisRef = this;
      PreviewingVideoGraph videoGraph =
          previewingVideoGraphFactory.create(
              context,
              inputColorInfo,
              outputColorInfo,
              DebugViewProvider.NONE,
              /* listener= */ thisRef,
              /* listenerExecutor= */ handler::post,
              /* compositionEffects= */ ImmutableList.of(),
              /* initialTimestampOffsetUs= */ 0);
      int videoGraphInputId = videoGraph.registerInput();
      videoFrameProcessor = videoGraph.getProcessor(videoGraphInputId);

      if (currentSurfaceAndSize != null) {
        Size outputSurfaceSize = currentSurfaceAndSize.second;
        videoGraph.setOutputSurfaceInfo(
            new SurfaceInfo(
                currentSurfaceAndSize.first,
                outputSurfaceSize.getWidth(),
                outputSurfaceSize.getHeight()));
      }
      videoEffects = new ArrayList<>();
      // MediaCodec applies rotation after API 21
      rotationEffect =
          Util.SDK_INT < 21 && sourceFormat.rotationDegrees != 0
              ? ScaleAndRotateAccessor.createRotationEffect(sourceFormat.rotationDegrees)
              : null;
    }

    // VideoSink impl

    @Override
    public void flush() {
      videoFrameProcessor.flush();
      processedFramesBufferTimestampsUs.clear();
      streamOffsets.clear();
      handler.removeCallbacksAndMessages(/* token= */ null);
      videoFrameReleaseControl.reset();
      if (registeredLastFrame) {
        registeredLastFrame = false;
        processedLastFrame = false;
        releasedLastFrame = false;
      }
    }

    @Override
    public boolean isReady() {
      return videoFrameReleaseControl.isReady(/* rendererReady= */ true);
    }

    @Override
    public boolean isEnded() {
      return releasedLastFrame;
    }

    @Override
    public void registerInputStream(@InputType int inputType, Format format) {
      if (inputType != INPUT_TYPE_SURFACE) {
        throw new UnsupportedOperationException("Unsupported input type " + inputType);
      }
      this.inputFormat = format;
      maybeRegisterInputStream();

      if (registeredLastFrame) {
        registeredLastFrame = false;
        processedLastFrame = false;
        releasedLastFrame = false;
      }
    }

    @Override
    public void setListener(Listener listener, Executor executor) {
      if (Util.areEqual(this.listener, listener)) {
        checkState(Util.areEqual(listenerExecutor, executor));
        return;
      }
      this.listener = listener;
      this.listenerExecutor = executor;
    }

    @Override
    public boolean isFrameDropAllowedOnInput() {
      return Util.isFrameDropAllowedOnSurfaceInput(context);
    }

    @Override
    public Surface getInputSurface() {
      return videoFrameProcessor.getInputSurface();
    }

    @Override
    public long registerInputFrame(long framePresentationTimeUs, boolean isLastFrame) {
      checkState(videoFrameProcessorMaxPendingFrameCount != C.LENGTH_UNSET);
      if (videoFrameProcessor.getPendingInputFrameCount()
          >= videoFrameProcessorMaxPendingFrameCount) {
        return C.TIME_UNSET;
      }
      if (!videoFrameProcessor.registerInputFrame()) {
        return C.TIME_UNSET;
      }
      // The sink takes in frames with monotonically increasing, non-offset frame
      // timestamps. That is, with two ten-second long videos, the first frame of the second video
      // should bear a timestamp of 10s seen from VideoFrameProcessor; while in ExoPlayer, the
      // timestamp of the said frame would be 0s, but the streamOffset is incremented 10s to include
      // the duration of the first video. Thus this correction is need to correct for the different
      // handling of presentation timestamps in ExoPlayer and VideoFrameProcessor.
      long bufferPresentationTimeUs = framePresentationTimeUs + inputStreamOffsetUs;
      if (pendingInputStreamOffsetChange) {
        streamOffsets.add(bufferPresentationTimeUs, inputStreamOffsetUs);
        pendingInputStreamOffsetChange = false;
      }
      if (isLastFrame) {
        registeredLastFrame = true;
        lastCodecBufferPresentationTimestampUs = bufferPresentationTimeUs;
      }
      return bufferPresentationTimeUs * 1000;
    }

    @Override
    public boolean queueBitmap(Bitmap inputBitmap, TimestampIterator inStreamOffsetsUs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) {
      while (!processedFramesBufferTimestampsUs.isEmpty()) {
        long bufferPresentationTimeUs = processedFramesBufferTimestampsUs.element();
        // check whether this buffer comes with a new stream offset.
        if (maybeUpdateOutputStreamOffset(bufferPresentationTimeUs)) {
          videoFrameReleaseControl.onProcessedStreamChange();
        }
        long framePresentationTimeUs = bufferPresentationTimeUs - outputStreamOffsetUs;
        boolean isLastFrame = processedLastFrame && processedFramesBufferTimestampsUs.size() == 1;
        @VideoFrameReleaseControl.FrameReleaseAction
        int frameReleaseAction =
            videoFrameReleaseControl.getFrameReleaseAction(
                bufferPresentationTimeUs,
                positionUs,
                elapsedRealtimeUs,
                outputStreamOffsetUs,
                isLastFrame,
                videoFrameReleaseInfo);
        switch (frameReleaseAction) {
          case VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER:
            return;
          case VideoFrameReleaseControl.FRAME_RELEASE_SKIP:
          case VideoFrameReleaseControl.FRAME_RELEASE_SKIP_TO_KEYFRAME:
            // Skipped frames are dropped.
          case VideoFrameReleaseControl.FRAME_RELEASE_DROP:
          case VideoFrameReleaseControl.FRAME_RELEASE_DROP_TO_KEYFRAME:
            // TODO b/293873191 - Handle very late buffers and drop to key frame. Need to flush
            //  VideoFrameProcessor input frames in this case.
            dropFrame(isLastFrame);
            break;
          case VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY:
          case VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED:
            renderFrame(
                framePresentationTimeUs, bufferPresentationTimeUs, frameReleaseAction, isLastFrame);
            break;
          default:
            throw new IllegalStateException(String.valueOf(frameReleaseAction));
        }
      }
    }

    @Override
    public void setPlaybackSpeed(float speed) {
      checkArgument(speed >= 0.0);
      videoFrameReleaseControl.setPlaybackSpeed(speed);
    }

    // VideoGraph.Listener methods

    @Override
    public void onOutputSizeChanged(int width, int height) {
      VideoSize newVideoSize = new VideoSize(width, height);
      if (!processedFrameSize.equals(newVideoSize)) {
        processedFrameSize = newVideoSize;
        pendingVideoSizeChange = true;
      }
    }

    @Override
    public void onOutputFrameAvailableForRendering(long presentationTimeUs) {
      if (pendingVideoSizeChange) {
        videoSizeChanges.add(presentationTimeUs, processedFrameSize);
        pendingVideoSizeChange = false;
      }
      if (registeredLastFrame) {
        checkState(lastCodecBufferPresentationTimestampUs != C.TIME_UNSET);
      }
      processedFramesBufferTimestampsUs.add(presentationTimeUs);
      // TODO b/257464707 - Support extensively modified media.
      if (registeredLastFrame && presentationTimeUs >= lastCodecBufferPresentationTimestampUs) {
        processedLastFrame = true;
      }
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      if (listener == null || listenerExecutor == null) {
        return;
      }
      listenerExecutor.execute(
          () -> {
            if (listener != null) {
              listener.onError(
                  /* videoSink= */ this,
                  new VideoSink.VideoSinkException(
                      exception,
                      new Format.Builder()
                          .setSampleMimeType(MimeTypes.VIDEO_RAW)
                          .setWidth(processedFrameSize.width)
                          .setHeight(processedFrameSize.height)
                          .build()));
            }
          });
    }

    @Override
    public void onEnded(long finalFramePresentationTimeUs) {
      throw new IllegalStateException();
    }

    // Other methods

    public void release() {
      videoFrameProcessor.release();
      handler.removeCallbacksAndMessages(/* token= */ null);
      streamOffsets.clear();
      processedFramesBufferTimestampsUs.clear();
    }

    /** Sets the {@linkplain Effect video effects} to apply immediately. */
    public void setVideoEffects(List<Effect> videoEffects) {
      setPendingVideoEffects(videoEffects);
      maybeRegisterInputStream();
    }

    /**
     * Sets the {@linkplain Effect video effects} to apply when the next stream is {@linkplain
     * #registerInputStream(int, Format) registered}.
     */
    public void setPendingVideoEffects(List<Effect> videoEffects) {
      this.videoEffects.clear();
      this.videoEffects.addAll(videoEffects);
    }

    public void setStreamOffsetUs(long streamOffsetUs) {
      pendingInputStreamOffsetChange = inputStreamOffsetUs != streamOffsetUs;
      inputStreamOffsetUs = streamOffsetUs;
    }

    public void setVideoFrameMetadataListener(
        VideoFrameMetadataListener videoFrameMetadataListener) {
      this.videoFrameMetadataListener = videoFrameMetadataListener;
    }

    private void maybeRegisterInputStream() {
      if (inputFormat == null) {
        return;
      }

      ArrayList<Effect> effects = new ArrayList<>();
      if (rotationEffect != null) {
        effects.add(rotationEffect);
      }
      effects.addAll(videoEffects);
      Format inputFormat = checkNotNull(this.inputFormat);
      videoFrameProcessor.registerInputStream(
          VideoFrameProcessor.INPUT_TYPE_SURFACE,
          effects,
          new FrameInfo.Builder(inputFormat.width, inputFormat.height)
              .setPixelWidthHeightRatio(inputFormat.pixelWidthHeightRatio)
              .build());
    }

    /**
     * Sets the output surface info.
     *
     * @param outputSurface The {@link Surface} to which {@link VideoFrameProcessor} outputs.
     * @param outputResolution The {@link Size} of the output resolution.
     */
    public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
      if (currentSurfaceAndSize != null
          && currentSurfaceAndSize.first.equals(outputSurface)
          && currentSurfaceAndSize.second.equals(outputResolution)) {
        return;
      }
      videoFrameReleaseControl.setOutputSurface(outputSurface);
      currentSurfaceAndSize = Pair.create(outputSurface, outputResolution);
      videoFrameProcessor.setOutputSurfaceInfo(
          new SurfaceInfo(
              outputSurface, outputResolution.getWidth(), outputResolution.getHeight()));
    }

    /** Clears the output surface info. */
    public void clearOutputSurfaceInfo() {
      videoFrameProcessor.setOutputSurfaceInfo(null);
      currentSurfaceAndSize = null;
    }

    private boolean maybeUpdateOutputStreamOffset(long bufferPresentationTimeUs) {
      boolean updatedOffset = false;
      @Nullable Long newOutputStreamOffsetUs = streamOffsets.pollFloor(bufferPresentationTimeUs);
      if (newOutputStreamOffsetUs != null && newOutputStreamOffsetUs != outputStreamOffsetUs) {
        outputStreamOffsetUs = newOutputStreamOffsetUs;
        updatedOffset = true;
      }
      return updatedOffset;
    }

    private void dropFrame(boolean isLastFrame) {
      if (listenerExecutor != null) {
        listenerExecutor.execute(
            () -> {
              if (listener != null) {
                listener.onFrameDropped(this);
              }
            });
      }
      releaseProcessedFrameInternal(VideoFrameProcessor.DROP_OUTPUT_FRAME, isLastFrame);
    }

    private void renderFrame(
        long framePresentationTimeUs,
        long bufferPresentationTimeUs,
        @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction,
        boolean isLastFrame) {
      if (videoFrameMetadataListener != null) {
        videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
            framePresentationTimeUs,
            frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY
                ? Clock.DEFAULT.nanoTime()
                : videoFrameReleaseInfo.getReleaseTimeNs(),
            checkNotNull(inputFormat),
            /* mediaFormat= */ null);
      }
      if (videoFrameReleaseControl.onFrameReleasedIsFirstFrame() && listenerExecutor != null) {
        listenerExecutor.execute(
            () -> {
              if (listener != null) {
                listener.onFirstFrameRendered(/* videoSink= */ this);
              }
            });
      }
      releaseProcessedFrameInternal(
          frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY
              ? VideoFrameProcessor.RENDER_OUTPUT_FRAME_IMMEDIATELY
              : videoFrameReleaseInfo.getReleaseTimeNs(),
          isLastFrame);

      maybeNotifyVideoSizeChanged(bufferPresentationTimeUs);
    }

    private void releaseProcessedFrameInternal(long releaseTimeNs, boolean isLastFrame) {
      videoFrameProcessor.renderOutputFrame(releaseTimeNs);
      processedFramesBufferTimestampsUs.remove();
      if (isLastFrame) {
        releasedLastFrame = true;
      }
    }

    private void maybeNotifyVideoSizeChanged(long bufferPresentationTimeUs) {
      if (onVideoSizeChangedCalled || listener == null) {
        return;
      }

      @Nullable VideoSize videoSize = videoSizeChanges.pollFloor(bufferPresentationTimeUs);
      if (videoSize == null) {
        return;
      }

      if (!videoSize.equals(VideoSize.UNKNOWN) && !videoSize.equals(reportedVideoSize)) {
        reportedVideoSize = videoSize;
        checkNotNull(listenerExecutor)
            .execute(() -> checkNotNull(listener).onVideoSizeChanged(this, videoSize));
      }
      onVideoSizeChangedCalled = true;
    }

    private static final class ScaleAndRotateAccessor {
      private static @MonotonicNonNull Constructor<?>
          scaleAndRotateTransformationBuilderConstructor;
      private static @MonotonicNonNull Method setRotationMethod;
      private static @MonotonicNonNull Method buildScaleAndRotateTransformationMethod;

      public static Effect createRotationEffect(float rotationDegrees) {
        try {
          prepare();
          Object builder = scaleAndRotateTransformationBuilderConstructor.newInstance();
          setRotationMethod.invoke(builder, rotationDegrees);
          return (Effect) checkNotNull(buildScaleAndRotateTransformationMethod.invoke(builder));
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }

      @EnsuresNonNull({
        "scaleAndRotateTransformationBuilderConstructor",
        "setRotationMethod",
        "buildScaleAndRotateTransformationMethod"
      })
      private static void prepare() throws NoSuchMethodException, ClassNotFoundException {
        if (scaleAndRotateTransformationBuilderConstructor == null
            || setRotationMethod == null
            || buildScaleAndRotateTransformationMethod == null) {
          // TODO: b/284964524 - Add LINT and proguard checks for media3.effect reflection.
          Class<?> scaleAndRotateTransformationBuilderClass =
              Class.forName(
                  "com.google.android.exoplayer2.effect.ScaleAndRotateTransformation$Builder");
          scaleAndRotateTransformationBuilderConstructor =
              scaleAndRotateTransformationBuilderClass.getConstructor();
          setRotationMethod =
              scaleAndRotateTransformationBuilderClass.getMethod("setRotationDegrees", float.class);
          buildScaleAndRotateTransformationMethod =
              scaleAndRotateTransformationBuilderClass.getMethod("build");
        }
      }
    }
  }

  /**
   * Delays reflection for loading a {@linkplain PreviewingVideoGraph.Factory
   * PreviewingSingleInputVideoGraph} instance.
   */
  private static final class ReflectivePreviewingSingleInputVideoGraphFactory
      implements PreviewingVideoGraph.Factory {

    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    public ReflectivePreviewingSingleInputVideoGraphFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public PreviewingVideoGraph create(
        Context context,
        ColorInfo inputColorInfo,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs)
        throws VideoFrameProcessingException {
      try {
        Class<?> previewingSingleInputVideoGraphFactoryClass =
            Class.forName(
                "com.google.android.exoplayer2.effect.PreviewingSingleInputVideoGraph$Factory");
        PreviewingVideoGraph.Factory factory =
            (PreviewingVideoGraph.Factory)
                previewingSingleInputVideoGraphFactoryClass
                    .getConstructor(VideoFrameProcessor.Factory.class)
                    .newInstance(videoFrameProcessorFactory);
        return factory.create(
            context,
            inputColorInfo,
            outputColorInfo,
            debugViewProvider,
            listener,
            listenerExecutor,
            compositionEffects,
            initialTimestampOffsetUs);
      } catch (Exception e) {
        throw VideoFrameProcessingException.from(e);
      }
    }
  }
}
