/*
 * Where frames come from.
 *
 * The encoder downstream only ever wants VideoFrames at a fixed size, so each source is reduced
 * to the same shape: something to draw, its natural dimensions, an optional audio node, and a way
 * to release it. That is what lets an iPhone -- which cannot capture its screen at all -- still
 * put its camera or its photo library on the television through exactly the same pipeline.
 */
(function (root) {
  'use strict';

  function canShareScreen() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia);
  }

  function canUseCamera() {
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  }

  /** iOS reports itself as Macintosh on iPad, so touch points are the reliable tell. */
  function isApplePortable() {
    const agent = navigator.userAgent;
    if (/iPhone|iPod/.test(agent)) return true;
    return /Macintosh/.test(agent) && navigator.maxTouchPoints > 1;
  }

  async function acquireScreen(wantAudio) {
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: 30, max: 30 } },
      audio: wantAudio,
    });
    const track = stream.getVideoTracks()[0];
    if (!track) throw new Error('No screen was shared.');
    const settings = track.getSettings();
    return {
      kind: 'screen',
      label: 'Screen',
      stream,
      track,
      width: settings.width || 1280,
      height: settings.height || 720,
      fps: 30,
      audioTrack: stream.getAudioTracks()[0] || null,
      stop() { stream.getTracks().forEach((t) => t.stop()); },
    };
  }

  async function acquireCamera(facingMode, wantAudio) {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode,
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: 30, max: 30 },
      },
      audio: wantAudio,
    });
    const track = stream.getVideoTracks()[0];
    if (!track) throw new Error('No camera was available.');
    const settings = track.getSettings();
    return {
      kind: 'camera',
      label: 'Camera',
      stream,
      track,
      facingMode,
      width: settings.width || 1280,
      height: settings.height || 720,
      fps: 30,
      audioTrack: stream.getAudioTracks()[0] || null,
      stop() { stream.getTracks().forEach((t) => t.stop()); },
    };
  }

  /** A still photo. Encoded at a low rate: nothing is moving, so frames are nearly free. */
  async function openImage(file) {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.src = url;
    try {
      await image.decode();
    } catch (error) {
      URL.revokeObjectURL(url);
      throw new Error('That image could not be read: ' + file.name);
    }
    return {
      kind: 'image',
      label: file.name,
      element: image,
      width: image.naturalWidth,
      height: image.naturalHeight,
      fps: 2,
      audioTrack: null,
      stop() { URL.revokeObjectURL(url); },
    };
  }

  async function openVideoFile(file, wantAudio) {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.src = url;
    video.playsInline = true;
    video.muted = !wantAudio;   // muted playback is what iOS allows to autostart
    video.preload = 'auto';

    await new Promise((resolve, reject) => {
      video.onloadedmetadata = () => resolve();
      video.onerror = () => reject(new Error('That video could not be read: ' + file.name));
    });

    const source = {
      kind: 'video-file',
      label: file.name,
      element: video,
      width: video.videoWidth || 1280,
      height: video.videoHeight || 720,
      fps: 30,
      audioTrack: null,
      audioNode: null,
      audioContext: null,
      stop() {
        try { video.pause(); } catch (error) { /* already stopped */ }
        video.removeAttribute('src');
        video.load();
        URL.revokeObjectURL(url);
        if (source.audioContext) {
          source.audioContext.close().catch(() => {});
          source.audioContext = null;
        }
      },
    };

    if (wantAudio && typeof (window.AudioContext || window.webkitAudioContext) === 'function') {
      try {
        const AudioCtor = window.AudioContext || window.webkitAudioContext;
        const context = new AudioCtor();
        // Routing the element through Web Audio silences local playback, which is what we want:
        // the sound belongs on the television, not in the hand holding the phone.
        const node = context.createMediaElementSource(video);
        source.audioContext = context;
        source.audioNode = node;
        video.muted = false;
      } catch (error) {
        // Older Safari refuses createMediaElementSource on some files; carry on silently.
        source.audioNode = null;
      }
    }
    return source;
  }

  async function openFile(file, wantAudio) {
    const type = file.type || '';
    if (type.startsWith('image/')) return openImage(file);
    if (type.startsWith('video/')) return openVideoFile(file, wantAudio);
    // iOS sometimes hands over an empty type for files picked from the photo library.
    if (/\.(jpe?g|png|gif|webp|heic|heif|bmp)$/i.test(file.name)) return openImage(file);
    return openVideoFile(file, wantAudio);
  }

  root.BPlaySources = {
    canShareScreen,
    canUseCamera,
    isApplePortable,
    acquireScreen,
    acquireCamera,
    openFile,
  };
}(typeof globalThis !== 'undefined' ? globalThis : this));
