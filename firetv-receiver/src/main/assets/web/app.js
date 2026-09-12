/*
 * BPlay browser sender.
 *
 * Captures a screen with getDisplayMedia, encodes it to H.264 with WebCodecs, and pushes the
 * result down a WebSocket as the same packets the native sender apps send over TCP. Because the
 * encoder emits Annex-B, the Fire TV feeds those bytes straight to MediaCodec -- there is no
 * container, no transcode, and no separate code path for browsers on the receiving end.
 *
 * The byte-level format lives in wire.js, which loads first.
 */
'use strict';

const {
  TYPE,
  FLAG_KEYFRAME,
  encodeParams,
  buildHandshake,
  parseHandshakeResponse,
  buildPacket,
} = BPlayWire;

const TYPE_VIDEO = TYPE.VIDEO;
const TYPE_AUDIO_CONFIG = TYPE.AUDIO_CONFIG;
const TYPE_AUDIO = TYPE.AUDIO;
const TYPE_PING = TYPE.PING;
const TYPE_BYE = TYPE.BYE;
const TYPE_META = TYPE.META;

const STATUS_TEXT = {
  0: 'Connected',
  1: 'Wrong PIN. Check the four digits on the TV screen.',
  2: 'Another device is already mirroring to this TV.',
  3: 'The TV is running a different version of BPlay. Reload this page.',
};

/** Force a keyframe at least this often so the TV can join or recover quickly. */
const KEYFRAME_INTERVAL_MS = 1000;

/**
 * Frames allowed in the encoder's queue before we start dropping. Latency, not smoothness, is
 * what makes a mirrored screen usable, so a backlog is always worth throwing away.
 */
const MAX_ENCODE_QUEUE = 2;

const ui = {
  tvName: document.getElementById('tv-name'),
  version: document.getElementById('version'),
  panelUnsupported: document.getElementById('panel-unsupported'),
  unsupportedReason: document.getElementById('unsupported-reason'),
  panelSetup: document.getElementById('panel-setup'),
  panelLive: document.getElementById('panel-live'),
  pin: document.getElementById('pin'),
  quality: document.getElementById('quality'),
  wantAudio: document.getElementById('want-audio'),
  audioNote: document.getElementById('audio-note'),
  start: document.getElementById('start'),
  stop: document.getElementById('stop'),
  setupError: document.getElementById('setup-error'),
  liveTitle: document.getElementById('live-title'),
  liveDetail: document.getElementById('live-detail'),
  statSize: document.getElementById('stat-size'),
  statFps: document.getElementById('stat-fps'),
  statRate: document.getElementById('stat-rate'),
  statDrop: document.getElementById('stat-drop'),
};

// ---------------------------------------------------------------------------
// Capability detection
// ---------------------------------------------------------------------------

function missingCapability() {
  if (!window.isSecureContext) {
    return 'This page was not loaded over HTTPS, so the browser blocks screen capture. '
      + 'Open the https:// address shown on the TV.';
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
    return 'This browser has no screen-capture API. On iPhone and iPad, no browser does.';
  }
  if (typeof window.VideoEncoder === 'undefined') {
    return 'This browser has no WebCodecs video encoder. Chrome 94+, Edge 94+, '
      + 'Safari 16.4+ and Firefox 133+ all have one.';
  }
  return null;
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

class MirrorSender {
  constructor() {
    this.socket = null;
    this.stream = null;
    this.videoEncoder = null;
    this.audioEncoder = null;
    this.running = false;
    this.startedAt = 0;
    this.lastKeyframeAt = 0;
    this.framesSent = 0;
    this.framesDropped = 0;
    this.bytesSent = 0;
    this.statsTimer = null;
    this.pingTimer = null;
    this.abort = null;
  }

  async start({ pin, maxHeight, wantAudio }) {
    this.stream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: 30, max: 30 } },
      audio: wantAudio,
    });

    const videoTrack = this.stream.getVideoTracks()[0];
    if (!videoTrack) throw new Error('No screen was shared.');
    // Fires when the user hits the browser's own "Stop sharing" bar.
    videoTrack.addEventListener('ended', () => this.stop('You stopped sharing'));

    const settings = videoTrack.getSettings();
    const source = {
      width: settings.width || 1280,
      height: settings.height || 720,
    };

    await this.connect(pin, source);

    // Both ceilings apply: the TV's limit and whatever the user picked. Taking the TV's alone
    // would silently ignore someone who chose 720p to get a smoother picture.
    const ceiling = Math.min(maxHeight, this.tvMaxHeight || maxHeight);
    const size = scaleToFit(source.width, source.height, ceiling);
    this.width = size.width;
    this.height = size.height;

    this.startedAt = performance.now();
    this.running = true;
    this.startVideo(videoTrack, size);

    const audioTrack = this.stream.getAudioTracks()[0];
    if (audioTrack) {
      this.startAudio(audioTrack);
    }

    this.pingTimer = setInterval(() => this.send(buildPacket(TYPE_PING, 0, 0, new Uint8Array(0))),
      2000);
    this.statsTimer = setInterval(() => this.reportStats(), 1000);
  }

  connect(pin, source) {
    return new Promise((resolve, reject) => {
      const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/stream';
      const socket = new WebSocket(url);
      socket.binaryType = 'arraybuffer';
      this.socket = socket;

      const timeout = setTimeout(() => {
        socket.close();
        reject(new Error('The TV did not answer. Is BPlay still open on it?'));
      }, 10000);

      socket.onopen = () => {
        socket.send(buildHandshake({
          pin: pin || '',
          name: describeThisDevice(),
          platform: 'web',
          width: source.width,
          height: source.height,
          fps: 30,
          vcodec: 'video/avc',
        }));
      };

      socket.onmessage = (event) => {
        clearTimeout(timeout);
        let response;
        try {
          response = parseHandshakeResponse(event.data);
        } catch (error) {
          reject(error);
          return;
        }
        if (response.status !== 0) {
          const message = response.params.error
            || STATUS_TEXT[response.status]
            || ('The TV refused the connection (' + response.status + ')');
          reject(new Error(message));
          return;
        }
        this.tvName = response.params.name || 'your Fire TV';
        this.tvMaxHeight = parseInt(response.params.maxHeight, 10) || 0;
        this.tvMaxBitrate = parseInt(response.params.maxBitrate, 10) || 8000000;
        // From here on, messages are not expected: the TV only talks during the handshake.
        socket.onmessage = null;
        resolve();
      };

      socket.onerror = () => {
        clearTimeout(timeout);
        reject(new Error('Could not reach the TV. Check that both devices are on the same '
          + 'Wi-Fi network.'));
      };

      socket.onclose = () => {
        clearTimeout(timeout);
        if (this.running) this.stop('The TV closed the connection');
      };
    });
  }

  startVideo(track, size) {
    const bitrate = Math.min(bitrateFor(size.height), this.tvMaxBitrate || 8000000);

    this.videoEncoder = new VideoEncoder({
      output: (chunk) => {
        const payload = new Uint8Array(chunk.byteLength);
        chunk.copyTo(payload);
        const flags = chunk.type === 'key' ? FLAG_KEYFRAME : 0;
        this.send(buildPacket(TYPE_VIDEO, flags, chunk.timestamp, payload));
        this.framesSent++;
        this.bytesSent += payload.length;
      },
      error: (error) => this.stop('Encoder error: ' + error.message),
    });

    this.videoEncoder.configure({
      codec: codecFor(size.height),
      // Annex-B inlines SPS/PPS ahead of every keyframe, which is exactly what MediaCodec on the
      // TV wants and saves sending a separate config packet.
      avc: { format: 'annexb' },
      width: size.width,
      height: size.height,
      bitrate,
      framerate: 30,
      latencyMode: 'realtime',
    });

    this.send(buildPacket(TYPE_META, 0, 0,
      encodeParams({ width: size.width, height: size.height })));

    this.pumpVideo(track, size).catch((error) => {
      if (this.running) this.stop('Capture stopped: ' + error.message);
    });
  }

  /**
   * Two ways to get frames out of a MediaStreamTrack.
   *
   * MediaStreamTrackProcessor is the good one -- real VideoFrames with real timestamps, no copy
   * through a canvas -- but it is Chromium-only. Everywhere else, a hidden <video> painted into a
   * canvas produces the same VideoFrames at some cost in CPU.
   */
  async pumpVideo(track, size) {
    if ('MediaStreamTrackProcessor' in window) {
      const reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
      this.abort = () => reader.cancel().catch(() => {});
      while (this.running) {
        const { value: frame, done } = await reader.read();
        if (done) break;
        this.encodeFrame(frame);
      }
      return;
    }

    const video = document.createElement('video');
    video.srcObject = new MediaStream([track]);
    video.muted = true;
    video.playsInline = true;
    await video.play();

    const canvas = document.createElement('canvas');
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext('2d', { alpha: false, desynchronized: true });

    const startedAt = performance.now();
    const drawOnce = () => {
      if (!this.running) return;
      context.drawImage(video, 0, 0, size.width, size.height);
      const frame = new VideoFrame(canvas, {
        timestamp: Math.round((performance.now() - startedAt) * 1000),
      });
      this.encodeFrame(frame);
      schedule();
    };
    const schedule = () => {
      if (!this.running) return;
      if (video.requestVideoFrameCallback) {
        video.requestVideoFrameCallback(drawOnce);
      } else {
        setTimeout(drawOnce, 1000 / 30);
      }
    };
    this.abort = () => {
      video.pause();
      video.srcObject = null;
    };
    schedule();
  }

  encodeFrame(frame) {
    if (!this.running || !this.videoEncoder || this.videoEncoder.state !== 'configured') {
      frame.close();
      return;
    }
    // Dropping here is deliberate. If encoding cannot keep up, sending stale frames anyway would
    // grow a queue that never drains and the mirror would fall further behind for good.
    if (this.videoEncoder.encodeQueueSize > MAX_ENCODE_QUEUE) {
      this.framesDropped++;
      frame.close();
      return;
    }
    const now = performance.now();
    const forceKey = now - this.lastKeyframeAt >= KEYFRAME_INTERVAL_MS;
    if (forceKey) this.lastKeyframeAt = now;
    try {
      this.videoEncoder.encode(frame, { keyFrame: forceKey });
    } catch (error) {
      this.stop('Encoder rejected a frame: ' + error.message);
    } finally {
      frame.close();
    }
  }

  /** Audio is a bonus, never a requirement: any failure leaves the video mirror running. */
  startAudio(track) {
    if (typeof window.AudioEncoder === 'undefined'
      || !('MediaStreamTrackProcessor' in window)) {
      return;
    }
    try {
      this.audioEncoder = new AudioEncoder({
        output: (chunk) => {
          const payload = new Uint8Array(chunk.byteLength);
          chunk.copyTo(payload);
          this.send(buildPacket(TYPE_AUDIO, 0, chunk.timestamp, payload));
        },
        error: () => { this.audioEncoder = null; },
      });
      this.audioEncoder.configure({
        codec: 'opus',
        sampleRate: 48000,
        numberOfChannels: 2,
        bitrate: 128000,
      });

      // WebCodecs gives raw Opus packets with no identification header, so tell the TV what to
      // synthesise rather than sending codec-specific data that does not exist.
      this.send(buildPacket(TYPE_AUDIO_CONFIG, 0, 0,
        encodeParams({ codec: 'opus', sampleRate: 48000, channels: 2 })));

      const reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
      const pump = async () => {
        while (this.running && this.audioEncoder) {
          const { value: data, done } = await reader.read();
          if (done) break;
          try {
            if (this.audioEncoder.state === 'configured') this.audioEncoder.encode(data);
          } finally {
            data.close();
          }
        }
      };
      pump().catch(() => { this.audioEncoder = null; });
    } catch (error) {
      this.audioEncoder = null;
    }
  }

  send(bytes) {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    // Never let the socket's buffer become the place latency hides.
    if (socket.bufferedAmount > 4 * 1024 * 1024) {
      this.framesDropped++;
      return;
    }
    socket.send(bytes);
  }

  reportStats() {
    const seconds = 1;
    const fps = this.framesSent;
    const mbps = (this.bytesSent * 8) / 1e6 / seconds;
    this.framesSent = 0;
    this.bytesSent = 0;
    ui.statFps.textContent = fps + ' fps';
    ui.statRate.textContent = mbps.toFixed(1) + ' Mbps';
    ui.statDrop.textContent = String(this.framesDropped);
    ui.statSize.textContent = this.width + '×' + this.height;
    ui.liveDetail.textContent = 'Connected to ' + (this.tvName || 'your Fire TV');
  }

  stop(reason) {
    if (!this.running && !this.socket) return;
    this.running = false;

    clearInterval(this.pingTimer);
    clearInterval(this.statsTimer);
    if (this.abort) {
      try { this.abort(); } catch (error) { /* the track is already gone */ }
      this.abort = null;
    }

    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      try {
        this.socket.send(buildPacket(TYPE_BYE, 0, 0, new Uint8Array(0)));
      } catch (error) { /* closing anyway */ }
    }
    closeQuietly(this.videoEncoder);
    closeQuietly(this.audioEncoder);
    this.videoEncoder = null;
    this.audioEncoder = null;

    if (this.stream) {
      this.stream.getTracks().forEach((track) => track.stop());
      this.stream = null;
    }
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.close();
      this.socket = null;
    }
    onSessionEnded(reason);
  }
}

function closeQuietly(encoder) {
  if (!encoder) return;
  try {
    if (encoder.state !== 'closed') encoder.close();
  } catch (error) {
    // Already closed, or never configured.
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Scales to the TV's ceiling, keeping aspect and even dimensions (H.264 requires both). */
function scaleToFit(width, height, maxHeight) {
  const limit = maxHeight > 0 ? maxHeight : 1080;
  if (height <= limit) {
    return { width: even(width), height: even(height) };
  }
  const scale = limit / height;
  return { width: even(Math.round(width * scale)), height: even(limit) };
}

function even(value) {
  const rounded = Math.max(2, Math.round(value));
  return rounded % 2 === 0 ? rounded : rounded - 1;
}

/**
 * Constrained Baseline, at the lowest level that covers the resolution. Fire TV hardware decoders
 * all handle Baseline; the fancier profiles are where old sticks start falling back to software.
 */
function codecFor(height) {
  if (height <= 480) return 'avc1.42E01E'; // level 3.0
  if (height <= 720) return 'avc1.42E01F'; // level 3.1
  return 'avc1.42E028';                    // level 4.0
}

function bitrateFor(height) {
  if (height <= 480) return 2000000;
  if (height <= 720) return 4000000;
  return 8000000;
}

/** A name the TV can show, without fingerprinting anything the user did not volunteer. */
function describeThisDevice() {
  const agent = navigator.userAgent;
  let platform = 'Computer';
  if (/Windows/i.test(agent)) platform = 'Windows PC';
  else if (/Macintosh|Mac OS X/i.test(agent)) platform = 'Mac';
  else if (/CrOS/i.test(agent)) platform = 'Chromebook';
  else if (/Android/i.test(agent)) platform = 'Android device';
  else if (/iPhone|iPad/i.test(agent)) platform = 'iOS device';
  else if (/Linux/i.test(agent)) platform = 'Linux PC';

  let browser = '';
  if (/Edg\//.test(agent)) browser = 'Edge';
  else if (/OPR\//.test(agent)) browser = 'Opera';
  else if (/Firefox\//.test(agent)) browser = 'Firefox';
  else if (/Chrome\//.test(agent)) browser = 'Chrome';
  else if (/Safari\//.test(agent)) browser = 'Safari';

  return browser ? platform + ' (' + browser + ')' : platform;
}

// ---------------------------------------------------------------------------
// UI wiring
// ---------------------------------------------------------------------------

let session = null;

function showSetupError(message) {
  ui.setupError.textContent = message;
  ui.setupError.hidden = !message;
}

function onSessionEnded(reason) {
  session = null;
  ui.panelLive.hidden = true;
  ui.panelSetup.hidden = false;
  ui.start.disabled = false;
  ui.start.textContent = 'Start mirroring';
  showSetupError(reason && reason !== 'You stopped sharing' ? reason : '');
}

async function beginMirroring() {
  showSetupError('');
  ui.start.disabled = true;
  ui.start.textContent = 'Asking for a screen…';

  session = new MirrorSender();
  try {
    await session.start({
      pin: ui.pin.value.trim(),
      maxHeight: parseInt(ui.quality.value, 10),
      wantAudio: ui.wantAudio.checked,
    });
    ui.panelSetup.hidden = true;
    ui.panelLive.hidden = false;
    ui.liveTitle.textContent = 'Mirroring to ' + (session.tvName || 'your Fire TV');
    if (session.tvName) ui.tvName.textContent = session.tvName;
  } catch (error) {
    const aborted = error && (error.name === 'NotAllowedError' || error.name === 'AbortError');
    if (session) session.stop(null);
    session = null;
    ui.start.disabled = false;
    ui.start.textContent = 'Start mirroring';
    showSetupError(aborted ? '' : (error.message || String(error)));
  }
}

function init() {
  const problem = missingCapability();
  if (problem) {
    ui.unsupportedReason.textContent = problem;
    ui.panelUnsupported.hidden = false;
    ui.panelSetup.hidden = true;
    return;
  }

  // The TV's QR code carries the PIN, so a scanned link needs no typing at all.
  const pinFromUrl = new URLSearchParams(location.search).get('pin');
  if (pinFromUrl) ui.pin.value = pinFromUrl.replace(/\D/g, '').slice(0, 4);

  const canSendAudio = typeof window.AudioEncoder !== 'undefined'
    && 'MediaStreamTrackProcessor' in window;
  if (!canSendAudio) {
    ui.wantAudio.checked = false;
    ui.wantAudio.disabled = true;
    ui.audioNote.textContent = '— this browser can\'t capture audio';
  } else {
    ui.audioNote.textContent = '— if the share dialog offers it';
  }

  ui.start.addEventListener('click', beginMirroring);
  ui.stop.addEventListener('click', () => session && session.stop('Stopped'));
  window.addEventListener('pagehide', () => session && session.stop(null));

  fetch('/info').then((response) => response.json()).then((info) => {
    if (info.name) ui.tvName.textContent = info.name;
    if (info.version) ui.version.textContent = 'BPlay ' + info.version;
    if (info.pinRequired === false) {
      ui.pin.placeholder = 'not needed';
      ui.pin.disabled = true;
    }
  }).catch(() => { /* cosmetic only */ });
}

init();
