//#!/usr/bin/env jbang
//DEPS org.bytedeco:javacv-platform:1.5.8
//DEPS org.slf4j:slf4j-api:1.7.36
//DEPS ch.qos.logback:logback-classic:1.2.11

//jbang --verbose videostreamcapture.java "rtsp://<user>:<password>@192.168.1.81:554/live/stream1" PT1M [snapshotPeriodSec]

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.avcodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class videostreamcapture {
    private static final Logger log = LoggerFactory.getLogger(videostreamcapture.class);

    public static void main(String... args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: jbang run videostreamcapture.java <rtsp_url> <period> [snapshotPeriodSec]");
            System.exit(1);
        }

        String url    = args[0];
        String period = args[1];
        int snapshotPeriodSec = (args.length >= 3)
            ? Integer.parseInt(args[2])
            : 1; // default: one snapshot per second

        String maskedUrl = maskCredentials(url);
        log.info("Starting capture for URL {} for duration {} (snapshot every {}s)",
                 maskedUrl, period, snapshotPeriodSec);

        Duration duration;
        try {
            duration = Duration.parse(period);
        } catch (DateTimeParseException e) {
            log.error("Invalid duration format '{}'. Use ISO‑8601 (e.g. PT1H, P1DT12H).", period);
            return;
        }

        // enable FFmpeg internal logs for detail
        FFmpegLogCallback.set();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
            grabber.setOption("rtsp_transport", "tcp");
            grabber.start();
            log.info("Grabber started: resolution={}x{} @ {}fps, audioChannels={}",
                     grabber.getImageWidth(),
                     grabber.getImageHeight(),
                     grabber.getFrameRate(),
                     grabber.getAudioChannels()
            );

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    "output.mp4",
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels())) {

                recorder.setFormat("mp4");
                recorder.setVideoCodec(grabber.getVideoCodec());
                // transcode audio from pcm_alaw to AAC for MP4 compatibility
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setAudioBitrate(128_000);
                recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                recorder.setFrameRate(grabber.getFrameRate());
                recorder.setSampleRate(grabber.getSampleRate());
                recorder.setAudioChannels(grabber.getAudioChannels());
                recorder.start();
                log.info("Recorder started (video codec={}, audio=AAC @ {}bps)",
                         grabber.getVideoCodec(), 128_000);

                // snapshot setup
                Java2DFrameConverter conv = new Java2DFrameConverter();
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
                long lastSnapshotMs = System.currentTimeMillis();
                int snapCount = 0;
                Instant end = Instant.now().plus(duration);

                while (Instant.now().isBefore(end)) {
                    Frame frame = grabber.grabFrame(true, true, true, false, true);
                    if (frame != null) {
                        // record to MP4
                        recorder.record(frame);

                        // snapshot at most once per snapshotPeriodSec
                        long nowMs = System.currentTimeMillis();
                        if (frame.image != null && nowMs - lastSnapshotMs >= snapshotPeriodSec * 1000L) {
                            BufferedImage img = conv.convert(frame);
                            String ts = ZonedDateTime.now(ZoneId.systemDefault()).format(fmt);
                            String snapName = String.format("snap-%s-%03d.jpg", ts, snapCount++);
                            ImageIO.write(img, "jpg", new File(snapName));
                            log.debug("Saved snapshot {}", snapName);
                            lastSnapshotMs = nowMs;
                        }
                    }
                }

                recorder.stop();
                log.info("Recorder stopped.");
            }

            grabber.stop();
            log.info("Grabber stopped. Finished recording {} seconds.", duration.toSeconds());
        }
    }

    private static String maskCredentials(String url) {
        try {
            URI uri = URI.create(url);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                return url.replaceFirst(userInfo + "@", "***:***@");
            }
        } catch (Exception ignored) {}
        return url;
    }
}
