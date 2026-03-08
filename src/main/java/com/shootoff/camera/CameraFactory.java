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

package com.shootoff.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamCompositeDriver;
import com.github.sarxos.webcam.WebcamDiscoveryService;
import com.github.sarxos.webcam.ds.buildin.WebcamDefaultDriver;
import com.github.sarxos.webcam.ds.ipcam.IpCamDevice;
import com.github.sarxos.webcam.ds.ipcam.IpCamDriver;
import com.github.sarxos.webcam.ds.v4l4j.V4l4jDriver;
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.IpCamera;
import com.shootoff.camera.cameratypes.SarxosCaptureCamera;
import com.shootoff.util.SystemInfo;

public final class CameraFactory {
	private static final Logger logger = LoggerFactory.getLogger(CameraFactory.class);

	// These are used in a hack to get this code to work on Mac.
	// On Mac several of the webcam-capture API's can only be
	// called on the main thread before a JavaFX thread is started
	// or the library will hopeless hang and take ShootOFF with it.
	// Our solution is to cache the things we need that will hang
	// the program on start-up. This has the side effect that the
	// cameras that are known when ShootOFF starts are the only
	// ones it will ever know on Mac.
	private static final boolean isMac;
	private static Webcam defaultWebcam = null;
	private static List<Camera> knownWebcams;
	// Cache of sarxos Webcam objects from initial discovery (Linux).
	// Re-calling Webcam.getWebcams() after stopping the discovery service
	// can fail, so we cache the result.
	private static List<Webcam> cachedSarxosWebcams = null;

	private static List<Camera> openCameras = Collections.synchronizedList(new ArrayList<>());

	// This list contains cameras that are not discoverable by webcam-capture
	// because they are non-UVC cameras with no webcam-capture driver (e.g.
	// Omnitrack). Adding such cameras to this list ensures they are returned
	// by CameraFactory.getWebcams().
	private final static List<Camera> registeredCameras = new ArrayList<>();

	public static void registerCamera(Camera camera) {
		registeredCameras.add(camera);
	}

	public static class CompositeDriver extends WebcamCompositeDriver {
		public CompositeDriver() {
			super();
			if (SystemInfo.isArm() && SystemInfo.isLinux()) {
				add(new V4l4jDriver());
			} else {
				add(new WebcamDefaultDriver());
			}
			add(new IpCamDriver());
		}
	}

	static {
		Webcam.setDriver(new CompositeDriver());

		if (SystemInfo.isMacOsX()) {
			isMac = true;
			defaultWebcam = Webcam.getDefault();
			knownWebcams = new ArrayList<>();

			for (final Webcam w : Webcam.getWebcams()) {
				final Camera c = (w.getDevice() instanceof IpCamDevice) ? new IpCamera(w)
						: new SarxosCaptureCamera(w.getName());

				knownWebcams.add(c);
			}
		} else {
			isMac = false;
			defaultWebcam = null;
			knownWebcams = null;
		}
	}

	public static Optional<Camera> getDefault() {
		Camera defaultCam;

		if (isMac) {
			if (defaultWebcam == null)
				defaultCam = null;
			else
				defaultCam = new SarxosCaptureCamera(defaultWebcam.getName());
		} else {
			// Use getWebcams() instead of Webcam.getDefault() to avoid
			// re-triggering discovery after we've stopped the service
			final List<Camera> all = getWebcams();
			defaultCam = all.isEmpty() ? null : all.get(0);
		}

		if (defaultCam == null && !registeredCameras.isEmpty()) {
			defaultCam = registeredCameras.get(0);
		}

		return Optional.ofNullable(defaultCam);
	}

	public static List<Camera> getWebcams() {
		if (isMac) return knownWebcams;

		final List<Camera> webcams = new ArrayList<>();

		if (cachedSarxosWebcams == null) {
			cachedSarxosWebcams = Webcam.getWebcams();

			// Stop the background discovery service to prevent it from leaking
			// V4L2 file descriptors on Linux. The bridj V4L2 driver opens devices
			// during periodic scans but doesn't always close them, exhausting the
			// 16-device limit and preventing ffmpeg from accessing cameras.
			stopDiscoveryService();
		}
		final List<Webcam> sarxosWebcams = cachedSarxosWebcams;

		int cameraIndex = 0;
		for (final Webcam w : sarxosWebcams) {
			final Camera c;
			if (w.getDevice() instanceof IpCamDevice)
				c = new IpCamera(w);
			else
				c = new SarxosCaptureCamera(w.getName(), cameraIndex);

			synchronized (openCameras) {
				// If we already have an open instance of the camera
				// go ahead and reuse it in this list as opposed to
				// the newly created camera
				final int i = openCameras.indexOf(c);

				if (logger.isTraceEnabled()) logger.trace("Looking in openCameras for {} found at {}", w.getName(), i);
				if (i >= 0) {
					webcams.add(openCameras.get(i));
				} else {
					webcams.add(c);
				}
			}

			cameraIndex++;
		}

		webcams.addAll(registeredCameras);

		return webcams;
	}

	public static void openCamerasRemove(Camera camera) {
		synchronized (openCameras) {
			openCameras.remove(camera);
		}
	}

	public static void openCamerasAdd(Camera camera) {
		synchronized (openCameras) {
			if (!openCameras.contains(camera)) openCameras.add(camera);
		}
	}

	private static void stopDiscoveryService() {
		try {
			final WebcamDiscoveryService ds = Webcam.getDiscoveryServiceRef();
			if (ds != null && ds.isRunning()) {
				ds.stop();
				logger.info("Stopped webcam discovery service to prevent V4L2 FD leak");
			}
		} catch (final Exception e) {
			logger.warn("Could not stop webcam discovery service", e);
		}
	}

	public static boolean isMac() {
		return isMac;
	}
}
