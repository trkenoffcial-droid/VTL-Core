package com.kevin.tiertagger.dev;

import com.kevin.tiertagger.TierTagger;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development only: streams captured frames into ffmpeg as raw RGBA, which encodes them to an
 * H.264 MP4 (1920x1080, padded if the window is a little smaller). No frame is written to disk.
 */
final class FrameRecorder {
    private static final int MAX_PENDING = 6;
    private static final byte[] END = new byte[0];

    private final String ffmpeg;
    private final File out;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_PENDING + 2);
    private final AtomicInteger inFlight = new AtomicInteger();
    private Process process;
    private Thread writer;
    private int width;
    private int height;
    private long frames;
    private volatile IOException failure;

    FrameRecorder(String ffmpeg, File out) {
        this.ffmpeg = ffmpeg;
        this.out = out;
    }

    /** False while the encoder is behind; the caller then keeps the clock still and asks again. */
    boolean ready() {
        return failure == null && inFlight.get() + queue.size() < MAX_PENDING;
    }

    long frames() {
        return frames;
    }

    /** Reads back the frame just rendered; it reaches ffmpeg in capture order. */
    void capture() {
        inFlight.incrementAndGet();
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), image -> {
            try {
                offer(image);
            } finally {
                image.close();
                inFlight.decrementAndGet();
            }
        });
    }

    private void offer(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (process == null) start(w, h);
        if (process == null) return;
        if (w != width || h != height) {
            TierTagger.getLogger().warn("[trailer] frame size changed to {}x{}, frame skipped", w, h);
            return;
        }
        if (image.format() != NativeImage.Format.RGBA) {
            TierTagger.getLogger().warn("[trailer] unexpected frame format {}, frame skipped", image.format());
            return;
        }
        byte[] bytes = new byte[w * h * 4];
        MemoryUtil.memByteBuffer(image.getPointer(), bytes.length).get(bytes);
        try {
            queue.put(bytes);
            frames++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(int w, int h) {
        width = w;
        height = h;
        out.getParentFile().mkdirs();
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-y", "-loglevel", "warning",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(VirtualClock.FPS), "-i", "-",
                "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease:flags=neighbor,"
                        + "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0d0718",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", out.getAbsolutePath()));
        try {
            process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(new File(out.getParentFile(), "ffmpeg.log"))
                    .start();
        } catch (IOException e) {
            failure = e;
            TierTagger.getLogger().error("[trailer] could not start ffmpeg ({})", ffmpeg, e);
            return;
        }
        OutputStream stdin = process.getOutputStream();
        writer = new Thread(() -> {
            try (stdin) {
                for (;;) {
                    byte[] frame = queue.take();
                    if (frame == END) break;
                    stdin.write(frame);
                }
            } catch (IOException e) {
                failure = e;
                TierTagger.getLogger().error("[trailer] writing to ffmpeg failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vtl-trailer-writer");
        writer.setDaemon(true);
        writer.start();
        TierTagger.getLogger().info("[trailer] encoding {}x{} frames to {}", w, h, out);
    }

    /** Waits for the last frames, closes ffmpeg's input and waits for the MP4, then runs {@code done}. */
    void finish(Runnable done) {
        Thread finisher = new Thread(() -> {
            try {
                while (inFlight.get() > 0) Thread.sleep(20);
                if (process != null) {
                    queue.put(END);
                    writer.join();
                    int code = process.waitFor();
                    TierTagger.getLogger().info("[trailer] ffmpeg exited with {} after {} frames ({} s): {}",
                            code, frames, frames / VirtualClock.FPS, out);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Minecraft.getInstance().execute(done);
        }, "vtl-trailer-finish");
        finisher.setDaemon(true);
        finisher.start();
    }
}
