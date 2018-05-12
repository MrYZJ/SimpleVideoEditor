package com.createchance.simplevideoeditor;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * ${DESC}
 *
 * @author gaochao1-iri
 * @date 25/03/2018
 */

public class VideoWatermarkAddAction extends AbstractAction {

    private static final String TAG = "VideoWatermarkAddAction";

    private long mFromMs;
    private long mDurationMs;
    private Bitmap mWatermark;
    private int mXPos;
    private int mYPos;

    private VideoWatermarkAddAction() {
        super(Constants.ACTION_ADD_WATER_MARK);
    }

    public File getInputFile() {
        return mInputFile;
    }

    public File getOutputFile() {
        return mOutputFile;
    }

    public long getFromMs() {
        return mFromMs;
    }

    public long getDurationMs() {
        return mDurationMs;
    }

    public Bitmap getWatermark() {
        return mWatermark;
    }

    public int getXPos() {
        return mXPos;
    }

    public int getYPos() {
        return mYPos;
    }

    @Override
    public void start(File inputFile) {
        super.start(inputFile);
        onStarted();

        WatermarkAddWorker watermarkAddWorker = new WatermarkAddWorker();
        WorkRunner.addTaskToBackground(watermarkAddWorker);
    }

    @Override
    public void release() {
        super.release();
        this.mWatermark.recycle();
    }

    public static class Builder {
        private VideoWatermarkAddAction watermarkAddAction = new VideoWatermarkAddAction();

        public Builder watermark(Bitmap watermark) {
            watermarkAddAction.mWatermark = watermark;

            return this;
        }

        public Builder atXPos(int posX) {
            watermarkAddAction.mXPos = posX;

            return this;
        }

        public Builder atYPos(int posY) {
            watermarkAddAction.mYPos = posY;

            return this;
        }

        public Builder from(long fromMs) {
            watermarkAddAction.mFromMs = fromMs;

            return this;
        }

        public Builder duration(long durationMs) {
            watermarkAddAction.mDurationMs = durationMs;

            return this;
        }

        public VideoWatermarkAddAction build() {
            return watermarkAddAction;
        }
    }

    public class WatermarkAddWorker implements Runnable {
        MediaCodec decoder;
        MediaCodec encoder;
        MediaExtractor mediaExtractor;
        MediaMuxer mediaMuxer;
        int inputAudioTrackId = -1;
        int inputVideoTrackId = -1;
        int outputAudioTrackId = -1;
        int outputVideoTrackId = -1;
        OutputSurface outputSurface;
        InputSurface inputSurface;
        int videoWidth, videoHeight;

        @Override
        public void run() {
            try {
                if (checkRational()) {
                    prepare();
                    addWatermark();
                } else {
                    Logger.e(TAG, "Action params error.");
                    onFailed();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                release();
            }

            Log.d(TAG, "Watermark add done.");
        }

        private boolean checkRational() {
            if (mInputFile != null &&
                    mInputFile.exists() &&
                    mInputFile.isFile() &&
                    mOutputFile != null &&
                    mWatermark != null &&
                    !mWatermark.isRecycled() &&
                    mFromMs >= 0 &&
                    mDurationMs >= 0) {
                MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
                mediaMetadataRetriever.setDataSource(mInputFile.getAbsolutePath());
                long duration = Long.valueOf(mediaMetadataRetriever.
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                mediaMetadataRetriever.release();
                if (mFromMs + mDurationMs > duration) {
                    Logger.e(TAG, "Video selected section of out of duration!");
                    return false;
                }

                if (mOutputFile.exists()) {
                    Logger.w(TAG, "WARNING: Output file: " + mOutputFile
                            + " already exists, we will override it!");
                }

                return true;
            }

            return false;
        }

        private void prepare() throws Exception {
            MediaFormat videoFormat = null;
            // get video info first.
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(mInputFile.getAbsolutePath());
            videoWidth = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            videoHeight = Integer.valueOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));

            // init media muxer
            mediaMuxer = new MediaMuxer(mOutputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // init media extractor
            mediaExtractor = new MediaExtractor();
            mediaExtractor.setDataSource(mInputFile.getAbsolutePath());
            for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
                MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
                String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video")) {
                    Logger.d("GAOCHAO", "video format: " + mediaFormat);
                    inputVideoTrackId = i;
                    outputVideoTrackId = mediaMuxer.addTrack(mediaFormat);
                    videoFormat = mediaFormat;
                } else if (mime.startsWith("audio")) {
                    inputAudioTrackId = i;
                    outputAudioTrackId = mediaMuxer.addTrack(mediaFormat);
                }
            }

            // check if we have found one video track from input file.
            if (inputVideoTrackId == -1) {
                throw new IllegalArgumentException("No video track in input file: " + mInputFile);
            }

            // start media muxer
            mediaMuxer.start();

            // init decoder and encoder
            MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 3000000);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            outputSurface = new OutputSurface(videoWidth, videoHeight);
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(videoFormat, outputSurface.getSurface(), null, 0);
            decoder.start();
        }

        private void addWatermark() {
            ByteBuffer[] decodeInputBuffers = decoder.getInputBuffers();
            ByteBuffer[] encodeOutputBuffers = encoder.getOutputBuffers();
            MediaCodec.BufferInfo decodeInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encodeInfo = new MediaCodec.BufferInfo();
            final long TIME_OUT = 5000;
            int decodeInputBufferId, decodeOutputBufferId, encodeOutputBufferId;
            boolean videoReadDone = false;
            boolean decodeDone = false;

            // write all audio data first.
            if (inputAudioTrackId != -1) {
                mediaExtractor.selectTrack(inputAudioTrackId);
                ByteBuffer audioBuffer = ByteBuffer.allocate(512 * 1024);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                while (true) {
                    int sampleSize = mediaExtractor.readSampleData(audioBuffer, 0);
                    if (sampleSize == -1) {
                        Log.d(TAG, "addWatermark, read end of audio.");
                        mediaExtractor.unselectTrack(inputAudioTrackId);
                        break;
                    }
                    audioInfo.offset = 0;
                    audioInfo.presentationTimeUs = mediaExtractor.getSampleTime();
                    audioInfo.flags = mediaExtractor.getSampleFlags();
                    audioInfo.size = sampleSize;
                    mediaMuxer.writeSampleData(outputAudioTrackId, audioBuffer, audioInfo);
                    mediaExtractor.advance();
                }
            } else {
                Log.e(TAG, "addWatermark, input video has no audio track, so skip.");
            }

            Log.d(TAG, "addWatermark, handle video track.");
            mediaExtractor.selectTrack(inputVideoTrackId);
            while (true) {
                if (!videoReadDone) {
                    Log.d(TAG, "addWatermark, reading video data.");
                    decodeInputBufferId = decoder.dequeueInputBuffer(TIME_OUT);
                    if (decodeInputBufferId < 0) {
                        Log.d(TAG, "addWatermark, no buffer now, try again later.");
                        continue;
                    }

                    ByteBuffer buffer = decodeInputBuffers[decodeInputBufferId];
                    buffer.clear();
                    int sampleSize = mediaExtractor.readSampleData(buffer, 0);
                    Log.d(TAG, "addWatermark, read video size: " + sampleSize);
                    if (sampleSize == -1) {
                        videoReadDone = true;
                        Log.d(TAG, "addWatermark, read video done.");
                        decoder.queueInputBuffer(
                                decodeInputBufferId,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        decoder.queueInputBuffer(
                                decodeInputBufferId,
                                0,
                                sampleSize,
                                mediaExtractor.getSampleTime(),
                                mediaExtractor.getSampleFlags()
                        );
                        // next video frame.
                        mediaExtractor.advance();
                    }
                }

                if (!decodeDone) {
                    Log.d(TAG, "addWatermark, dequeue decode output data.");
                    decodeOutputBufferId = decoder.dequeueOutputBuffer(decodeInfo, TIME_OUT);
                    switch (decodeOutputBufferId) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            decodeInputBuffers = decoder.getInputBuffers();
                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            break;
                        default:
                            if (decodeOutputBufferId >= 0) {
                                decoder.releaseOutputBuffer(decodeOutputBufferId, true);
                                if ((decodeInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    Logger.d(TAG, "Got decode eos, decode done!");
                                    encoder.signalEndOfInputStream();
                                    decodeDone = true;
                                } else {
                                    // This waits for the image and renders it after it arrives.
                                    outputSurface.awaitNewImage();
                                    outputSurface.drawImage();
                                    // Send it to the encoder.
                                    inputSurface.setPresentationTime(decodeInfo.presentationTimeUs * 1000);
                                    inputSurface.swapBuffers();
                                }
                            }
                            break;
                    }
                }

                while (true) {
                    Log.d(TAG, "addWatermark, reading encoder output data.");
                    encodeOutputBufferId = encoder.dequeueOutputBuffer(encodeInfo, TIME_OUT);
                    if (encodeOutputBufferId == -1) {
                        break;
                    } else if (encodeOutputBufferId >= 0) {
                        if ((encodeInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break;
                        }
                        ByteBuffer buffer = encodeOutputBuffers[encodeOutputBufferId];
                        mediaMuxer.writeSampleData(outputVideoTrackId, buffer, encodeInfo);
                        encoder.releaseOutputBuffer(encodeOutputBufferId, false);
                    }
                }

                if ((encodeInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "addWatermark, reach encode eos.");
                    break;
                }
            }

            onSucceeded();
        }

        private void release() {
            if (mediaMuxer != null) {
                mediaMuxer.release();
            }

            if (mediaExtractor != null) {
                mediaExtractor.release();
            }

            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }

            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        }
    }
}
