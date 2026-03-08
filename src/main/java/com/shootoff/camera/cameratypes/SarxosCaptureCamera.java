/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.camera.cameratypes;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.highgui.Highgui;
import org.opencv.highgui.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.Webcam;
import com.shootoff.camera.CameraFactory;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.Frame;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.NativeShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;

public class SarxosCaptureCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(SarxosCaptureCamera.class);

	public static final int CV_CAP_PROP_EXPOSURE = 15;

	private int cameraIndex = -1;
	private int discoveryIndex = -1;
	private String cameraName;
	private final VideoCapture camera;

	// Fallback: use ffmpeg process when OpenCV can't handle the device
	private Process ffmpegProcess = null;
	private InputStream ffmpegStream = null;
	private boolean usingFfmpegFallback = false;
	private int ffmpegWidth = 640;
	private int ffmpegHeight = 480;
	private String devicePath = null;

	private final AtomicBoolean closing = new AtomicBoolean(false);
	private static final Pattern DEV_VIDEO_PATTERN = Pattern.compile("/dev/video(\\d+)");

	private static int resolveDeviceIndex(String cameraName, int fallbackIndex) {
		final Matcher m = DEV_VIDEO_PATTERN.matcher(cameraName);
		if (m.find()) {
			final int deviceIndex = Integer.parseInt(m.group(1));
			if (deviceIndex != fallbackIndex) {
				LoggerFactory.getLogger(SarxosCaptureCamera.class).info(
					"Camera '{}': using /dev/video{} instead of discovery index {}",
					cameraName, deviceIndex, fallbackIndex);
			}
			return deviceIndex;
		}
		return fallbackIndex;
	}

	private static String extractDevicePath(String cameraName) {
		final Matcher m = DEV_VIDEO_PATTERN.matcher(cameraName);
		if (m.find()) return m.group();
		return null;
	}

	// For testing
	protected SarxosCaptureCamera() {
		camera = null;
	}

	public SarxosCaptureCamera(final String cameraName) {
		final List<Webcam> webcams = Webcam.getWebcams();
		int cameraIndex = -1;

		for (int i = 0; i < webcams.size(); i++) {
			if (webcams.get(i).getName().equals(cameraName)) {
				cameraIndex = i;
				break;
			}
		}

		if (cameraIndex < 0) throw new IllegalArgumentException("Camera not found: " + cameraName);

		camera = new VideoCapture();
		this.cameraName = cameraName;
		this.discoveryIndex = cameraIndex;
		this.cameraIndex = resolveDeviceIndex(cameraName, cameraIndex);
		this.devicePath = extractDevicePath(cameraName);
	}

	public SarxosCaptureCamera(final String cameraName, int cameraIndex) {
		if (cameraIndex < 0) throw new IllegalArgumentException("Camera not found: " + cameraName);

		camera = new VideoCapture();
		this.cameraName = cameraName;
		this.discoveryIndex = cameraIndex;
		this.cameraIndex = resolveDeviceIndex(cameraName, cameraIndex);
		this.devicePath = extractDevicePath(cameraName);
	}

	@Override
	public Frame getFrame() {
		if (usingFfmpegFallback) {
			return getFfmpegFrame();
		}

		final Mat frame = new Mat();
		try {
			if (!isOpen() || !camera.read(frame) || frame.size().height == 0 || frame.size().width == 0) return null;
		} catch (final Exception e) {
			return null;
		}

		final long currentFrameTimestamp = System.currentTimeMillis();
		frameCount++;
		return new Frame(frame, currentFrameTimestamp);
	}

	private Frame getFfmpegFrame() {
		try {
			if (ffmpegStream == null) return null;
			final int frameSize = ffmpegWidth * ffmpegHeight * 3;
			final byte[] buf = new byte[frameSize];
			int offset = 0;
			while (offset < frameSize) {
				final int read = ffmpegStream.read(buf, offset, frameSize - offset);
				if (read == -1) {
					logger.warn("ffmpeg stream ended");
					return null;
				}
				offset += read;
			}

			final Mat mat = new Mat(ffmpegHeight, ffmpegWidth, CvType.CV_8UC3);
			mat.put(0, 0, buf);

			final long currentFrameTimestamp = System.currentTimeMillis();
			frameCount++;
			return new Frame(mat, currentFrameTimestamp);
		} catch (final Exception e) {
			logger.error("Error reading ffmpeg frame", e);
			return null;
		}
	}

	@Override
	public BufferedImage getBufferedImage() {
		final Frame frame = getFrame();

		if (frame == null) {
			return null;
		} else {
			return frame.getOriginalBufferedImage();
		}
	}

	@Override
	public synchronized boolean open() {
		if (logger.isTraceEnabled())
			logger.trace("{} - open request isOpen {} closing {}", getName(), isOpen(), closing);

		if (isOpen() && !closing.get()) return true;

		closing.set(false);

		// Try OpenCV first
		boolean open = camera.open(cameraIndex);

		if (open) {
			// Verify we can actually read a frame
			final Mat testFrame = new Mat();
			boolean canRead = false;
			for (int i = 0; i < 3; i++) {
				if (camera.read(testFrame) && testFrame.size().height > 0) {
					canRead = true;
					break;
				}
				try { Thread.sleep(100); } catch (InterruptedException ignored) {}
			}
			if (!canRead) {
				logger.warn("OpenCV opened device {} but cannot read frames, trying ffmpeg fallback", cameraIndex);
				camera.release();
				open = false;
			}
		}

		if (!open && devicePath != null) {
			// Fall back to ffmpeg process-based capture
			open = openFfmpegFallback();
		}

		if (open) {
			if (!usingFfmpegFallback) {
				camera.set(5, 60);
			}
			CameraFactory.openCamerasAdd(this);
		}

		return open;
	}

	private boolean openFfmpegFallback() {
		logger.info("Attempting ffmpeg fallback for {}", devicePath);

		// Try 640x480 first, then 320x240
		final int[][] resolutions = {{640, 480}, {320, 240}};

		for (final int[] res : resolutions) {
			try {
				final ProcessBuilder pb = new ProcessBuilder(
					"ffmpeg",
					"-loglevel", "error",
					"-f", "v4l2",
					"-video_size", res[0] + "x" + res[1],
					"-i", devicePath,
					"-f", "rawvideo",
					"-pix_fmt", "bgr24",
					"-an",
					"-"
				);
				pb.redirectErrorStream(false);
				// Send ffmpeg's stderr to /dev/null to prevent buffer fill-up
				pb.redirectError(new java.io.File("/dev/null"));
				final Process proc = pb.start();

				// Read a test frame to verify it works
				final InputStream stream = proc.getInputStream();
				final int frameSize = res[0] * res[1] * 3;
				final byte[] testBuf = new byte[frameSize];
				int offset = 0;
				final long deadline = System.currentTimeMillis() + 5000;
				while (offset < frameSize && System.currentTimeMillis() < deadline) {
					final int read = stream.read(testBuf, offset, frameSize - offset);
					if (read == -1) break;
					offset += read;
				}

				if (offset == frameSize) {
					ffmpegProcess = proc;
					ffmpegStream = stream;
					ffmpegWidth = res[0];
					ffmpegHeight = res[1];
					usingFfmpegFallback = true;
					logger.info("ffmpeg fallback opened at {}x{} for {}", res[0], res[1], devicePath);

					// Ensure ffmpeg is killed when JVM exits
					Runtime.getRuntime().addShutdownHook(new Thread(() -> {
						if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
							ffmpegProcess.destroyForcibly();
						}
					}));

					return true;
				} else {
					logger.warn("ffmpeg fallback could not read full frame at {}x{} (got {} of {} bytes)",
						res[0], res[1], offset, frameSize);
					proc.destroyForcibly();
				}
			} catch (final Exception e) {
				logger.error("ffmpeg fallback failed at {}x{}", res[0], res[1], e);
			}
		}

		return false;
	}

	@Override
	public boolean isOpen() {
		if (usingFfmpegFallback) {
			return ffmpegProcess != null && ffmpegProcess.isAlive();
		}
		return camera.isOpened();
	}

	@Override
	public synchronized void close() {
		if (logger.isTraceEnabled())
			logger.trace("{} - close request isOpen {} closing {}", getName(), isOpen(), closing);

		if (isOpen() && !closing.get()) {
			closing.set(true);
			if (usingFfmpegFallback) {
				if (ffmpegProcess != null) {
					ffmpegProcess.destroyForcibly();
					ffmpegProcess = null;
					ffmpegStream = null;
				}
			} else {
				resetExposure();
				camera.release();
			}

			CameraFactory.openCamerasRemove(this);
			if (cameraEventListener.isPresent()) cameraEventListener.get().cameraClosed();

		} else if (isOpen() && closing.get()) {
			return;
		} else if (!isOpen()) {
			closing.set(false);
		}

		return;
	}

	@Override
	public String getName() {
		if (cameraName != null) return cameraName;
		return Webcam.getWebcams().get(discoveryIndex >= 0 ? discoveryIndex : cameraIndex).getName();
	}

	@Override
	public void setViewSize(final Dimension size) {
		if (!usingFfmpegFallback) {
			camera.set(Highgui.CV_CAP_PROP_FRAME_WIDTH, size.getWidth());
			camera.set(Highgui.CV_CAP_PROP_FRAME_HEIGHT, size.getHeight());
		}
	}

	@Override
	public Dimension getViewSize() {
		if (usingFfmpegFallback) {
			return new Dimension(ffmpegWidth, ffmpegHeight);
		}
		return new Dimension((int) camera.get(Highgui.CV_CAP_PROP_FRAME_WIDTH),
				(int) camera.get(Highgui.CV_CAP_PROP_FRAME_HEIGHT));
	}

	public void launchCameraSettings() {
		if (!usingFfmpegFallback) camera.set(Highgui.CV_CAP_PROP_SETTINGS, 1);
	}

	@Override
	public ShotDetector getPreferredShotDetector(final CameraManager cameraManager, final CameraView cameraView) {
		if (NativeShotDetector.isSystemSupported())
			return new NativeShotDetector(cameraManager, cameraView);
		else if (JavaShotDetector.isSystemSupported())
			return new JavaShotDetector(cameraManager, cameraView);
		else
			return null;
	}

	@Override
	public void run() {
		while (isOpen() && !closing.get()) {
			if (cameraEventListener.isPresent()) cameraEventListener.get().newFrame(getFrame());

			if (((int) (getFrameCount() % Math.min(getFPS(), 5)) == 0) && cameraState != CameraState.CALIBRATING) {
				estimateCameraFPS();
			}

		}

		if (logger.isTraceEnabled())
			logger.trace("{} camera closed during run thread isOpen {} closing {}", getName(), isOpen(), closing);

		if (!closing.get()) close();
	}

	@Override
	public boolean isLocked() {
		return false;
	}

	private Optional<Double> origExposure = Optional.empty();

	@Override
	public boolean supportsExposureAdjustment() {
		if (usingFfmpegFallback) return false;

		if (origExposure.isPresent()) return true;

		final double exp = camera.get(CV_CAP_PROP_EXPOSURE);

		if (logger.isInfoEnabled()) logger.info("Initial camera exposure {}", exp);

		if (exp == 0) return false;

		origExposure = Optional.of(exp);

		if (!decreaseExposure()) {
			resetExposure();
			origExposure = Optional.empty();
			return false;
		}

		resetExposure();
		return true;
	}

	@Override
	public boolean decreaseExposure() {
		if (usingFfmpegFallback) return false;

		final double curExp = camera.get(CV_CAP_PROP_EXPOSURE);
		final double newExp;
		if (curExp <= -10.0) {
			newExp = curExp + (.1 * curExp);
		} else {
			newExp = curExp - (.1 * curExp);
		}

		if (logger.isTraceEnabled()) logger.trace("curExp[ {} newExp {}", curExp, newExp);

		if (!((curExp < 0) == (newExp < 0)) || Math.abs(curExp - newExp) < .001f) return false;

		camera.set(CV_CAP_PROP_EXPOSURE, newExp);

		if (logger.isTraceEnabled()) logger.trace("Reducing exposure - curExp[ {} newExp {} res {}", curExp, newExp,
				camera.get(CV_CAP_PROP_EXPOSURE));

		if (curExp <= -10.0)
			return (camera.get(CV_CAP_PROP_EXPOSURE) < curExp);
		else
			return (Math.abs(camera.get(CV_CAP_PROP_EXPOSURE)) < Math.abs(curExp));
	}

	@Override
	public void resetExposure() {
		if (!usingFfmpegFallback && origExposure.isPresent()) camera.set(CV_CAP_PROP_EXPOSURE, origExposure.get());
	}

	@Override
	public boolean limitsFrames() {
		return false;
	}
}
