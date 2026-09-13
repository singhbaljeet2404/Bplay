/*
 * BPlay browser sender.
 *
 * Captures a source -- the screen, a camera, or a photo or video from this device -- encodes it to
 * H.264 with WebCodecs, and pushes the result down a WebSocket as the same packets the native
 * sender apps send over TCP. The encoder emits Annex-B, so the Fire TV feeds those bytes straight
 * to MediaCodec: no container, no transcode, and one decode path on the television regardless of
 * which of the three sources, or which platform, a frame came from.
 *
 * The byte-level format lives in wire.js and the source handling in sources.js; both load first.
 */
'use strict';

const {
  TYPE,
  FLAG_KEYFRAME,
  FLAG_LAST_CHUNK,
  encodeParams,
  decodeParams,
  buildHandshake,
  parseHandshakeResponse,
  buildPacket,
  parsePacket,
  buildMediaChunk,
} = BPlayWire;

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
  sourceScreen: document.getElementById('source-screen'),
  sourceCamera: document.getElementById('source-camera'),
  sourceFiles: document.getElementById('source-files'),
  sourceHint: document.getElementById('source-hint'),
  fileField: document.getElementById('file-field'),
  files: document.getElementById('files'),
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
  fileControls: document.getElementById('file-controls'),
  transport: document.getElementById('transport'),
  playPause: document.getElementById('play-pause'),
  seek: document.getElementById('seek'),
  elapsed: document.getElementById('elapsed'),
  duration: document.getElementById('duration'),
  filePosition: document.getElementById('file-position'),
  prev: document.getElementById('prev'),
  next: document.getElementById('next'),
  flip: document.getElementById('flip'),
};

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

class MirrorSender {
  constructor() {
    this.socket = null;
    this.source = null;
    this.videoEncoder = null;
    this.audioEncoder = null;
    this.audioContext = null;
    this.audioProcessor = null;
    this.running = false;
    this.lastKeyframeAt = 0;
    this.framesSent = 0;
    this.framesDropped = 0;
    this.bytesSent = 0;
    this.statsTimer = null;
    this.pingTimer = null;
    this.stopDrawing = null;
    // Bumped whenever the source changes, so a draw loop for an old source exits rather than
    // racing the new one into the encoder.
    this.generation = 0;
  }

  async start({ source, file, pin, maxHeight, wantAudio }) {
    this.maxHeight = maxHeight;
    this.wantAudio = wantAudio;

    // A file needs no dimensions in the handshake: if the TV plays it natively, nothing here
    // ever decodes it, and if it does not, the fallback opens it afterwards.
    await this.connect(pin, source || { width: 0, height: 0, fps: 30, kind: 'video-file' });
    this.running = true;

    if (file && this.tvSupportsMedia) {
      this.mode = 'native';
      this.playFileOnTv(file);
    } else {
      if (file) {
        // Older TV: fall back to transcoding, which at least shows something.
        source = await BPlaySources.openFile(file, wantAudio);
      }
      this.mode = 'stream';
      this.source = source;
      this.startVideo(source);
      this.startAudio(source);
    }

    this.pingTimer = setInterval(
      () => this.send(buildPacket(TYPE.PING, 0, 0, new Uint8Array(0))), 2000);
    this.statsTimer = setInterval(() => this.reportStats(), 1000);
  }

  // ---- native file playback -------------------------------------------

  /**
   * Hands the TV the file itself rather than a re-encode of it. Nothing is uploaded up front:
   * the TV asks for the ranges its player actually reads, so a three-gigabyte film starts as
   * quickly as a small one and seeking is real seeking.
   */
  playFileOnTv(file) {
    this.currentFile = file;
    this.mediaId = String(Date.now());
    this.send(buildPacket(TYPE.MEDIA_OFFER, 0, 0, encodeParams({
      id: this.mediaId,
      name: file.name,
      mime: file.type || guessMime(file.name),
      size: file.size,
      kind: isImageFile(file) ? 'image' : 'video',
    })));
    ui.statSize.textContent = formatBytes(file.size);
    ui.statRate.textContent = '\u2014';
    ui.statFps.textContent = 'Native';
  }

  onReceiverPacket(data) {
    let packet;
    try {
      packet = parsePacket(data);
    } catch (error) {
      return;
    }
    if (packet.type === TYPE.MEDIA_REQUEST) {
      this.serveRange(decodeParams(packet.payload));
    } else if (packet.type === TYPE.MEDIA_STATE) {
      onPlaybackState(decodeParams(packet.payload));
    }
  }

  /** Answers one range request by slicing the file, which never reads the whole thing. */
  async serveRange(params) {
    const requestId = parseInt(params.req, 10);
    const offset = parseInt(params.offset, 10);
    const length = parseInt(params.length, 10);
    const file = this.currentFile;
    if (!file || !Number.isFinite(requestId)) return;

    try {
      const slice = file.slice(offset, offset + length);
      const bytes = new Uint8Array(await slice.arrayBuffer());
      const MAX = 64 * 1024;
      for (let sent = 0; sent < bytes.length; sent += MAX) {
        const part = bytes.subarray(sent, Math.min(sent + MAX, bytes.length));
        const last = sent + MAX >= bytes.length;
        const ok = await this.sendReliable(buildPacket(TYPE.MEDIA_DATA,
          last ? FLAG_LAST_CHUNK : 0, 0, buildMediaChunk(requestId, offset + sent, part)));
        if (!ok) return;
      }
      if (bytes.length === 0) {
        await this.sendReliable(buildPacket(TYPE.MEDIA_DATA, FLAG_LAST_CHUNK, 0,
          buildMediaChunk(requestId, offset, new Uint8Array(0))));
      }
      this.send(buildPacket(TYPE.MEDIA_END, 0, 0, encodeParams({ req: requestId })));
    } catch (error) {
      this.send(buildPacket(TYPE.MEDIA_END, 0, 0,
        encodeParams({ req: requestId, error: 'Could not read the file on this device' })));
    }
  }

  sendControl(action, positionMs) {
    this.send(buildPacket(TYPE.MEDIA_CONTROL, 0, 0,
      encodeParams({ action, positionMs: Math.max(0, Math.round(positionMs || 0)) })));
  }

  /**
   * File bytes must never be dropped, so this waits for the socket to drain rather than
   * discarding like {@link send} does. A missing frame is a glitch; a missing byte is a
   * corrupt file the TV's decoder will choke on.
   */
  async sendReliable(bytes) {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    while (socket.bufferedAmount > 2 * 1024 * 1024) {
      await new Promise((resolve) => setTimeout(resolve, 20));
      if (!this.running || socket.readyState !== WebSocket.OPEN) return false;
    }
    socket.send(bytes);
    return true;
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
          name: describeThisDevice(source),
          platform: 'web',
          width: source.width,
          height: source.height,
          fps: source.fps,
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
          reject(new Error(response.params.error
            || STATUS_TEXT[response.status]
            || ('The TV refused the connection (' + response.status + ')')));
          return;
        }
        this.tvName = response.params.name || 'your Fire TV';
        this.tvMaxHeight = parseInt(response.params.maxHeight, 10) || 0;
        this.tvMaxBitrate = parseInt(response.params.maxBitrate, 10) || 8000000;
        this.tvSupportsMedia = response.params.media === '1';
        // The TV used to say nothing after the handshake. It does now: when it plays a file
        // itself it asks for byte ranges, and reports where playback has got to.
        socket.onmessage = (next) => this.onReceiverPacket(next.data);
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

  // ---- video ----------------------------------------------------------

  startVideo(source) {
    // Both ceilings apply: the TV's limit and whatever the user picked. Taking the TV's alone
    // would silently ignore someone who chose 720p to get a smoother picture.
    const ceiling = Math.min(this.maxHeight, this.tvMaxHeight || this.maxHeight);
    const size = scaleToFit(source.width, source.height, ceiling);
    this.width = size.width;
    this.height = size.height;

    const bitrate = Math.min(bitrateFor(size.height), this.tvMaxBitrate || 8000000);

    this.videoEncoder = new VideoEncoder({
      output: (chunk) => {
        const payload = new Uint8Array(chunk.byteLength);
        chunk.copyTo(payload);
        this.send(buildPacket(TYPE.VIDEO, chunk.type === 'key' ? FLAG_KEYFRAME : 0,
          chunk.timestamp, payload));
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
      framerate: source.fps,
      latencyMode: 'realtime',
    });

    this.send(buildPacket(TYPE.META, 0, 0,
      encodeParams({ width: size.width, height: size.height })));

    this.pump(source, size).catch((error) => {
      if (this.running) this.stop('Capture stopped: ' + error.message);
    });
  }

  /**
   * Turns whatever the source is into VideoFrames.
   *
   * MediaStreamTrackProcessor is the good path -- real frames with real timestamps and no copy --
   * but it is Chromium-only and does not exist for a file at all. Everywhere else, and for every
   * photo and video file, frames are painted into a canvas instead. That costs some CPU and is
   * the only reason an iPhone can put anything on the television at all.
   */
  async pump(source, size) {
    const generation = ++this.generation;
    const alive = () => this.running && this.generation === generation;

    if (source.track && 'MediaStreamTrackProcessor' in window) {
      const reader = new MediaStreamTrackProcessor({ track: source.track }).readable.getReader();
      this.stopDrawing = () => reader.cancel().catch(() => {});
      while (alive()) {
        const { value: frame, done } = await reader.read();
        if (done) break;
        if (!alive()) { frame.close(); break; }
        this.encodeFrame(frame);
      }
      return;
    }

    const drawable = await this.drawableFor(source);
    const canvas = document.createElement('canvas');
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext('2d', { alpha: false, desynchronized: true });

    const startedAt = performance.now();
    let stopped = false;
    this.stopDrawing = () => { stopped = true; };

    const drawOnce = () => {
      if (stopped || !alive()) return;
      try {
        context.drawImage(drawable, 0, 0, size.width, size.height);
      } catch (error) {
        return; // the element was torn down mid-frame
      }
      const frame = new VideoFrame(canvas, {
        timestamp: Math.round((performance.now() - startedAt) * 1000),
      });
      this.encodeFrame(frame);
      schedule();
    };

    const schedule = () => {
      if (stopped || !alive()) return;
      if (source.kind === 'image') {
        setTimeout(drawOnce, 1000 / source.fps);
      } else if (drawable.requestVideoFrameCallback) {
        drawable.requestVideoFrameCallback(drawOnce);
      } else {
        setTimeout(drawOnce, 1000 / source.fps);
      }
    };
    schedule();
  }

  /** An element the canvas can draw: the source's own, or one wrapping its MediaStream. */
  async drawableFor(source) {
    if (source.element) {
      if (source.kind === 'video-file') {
        source.element.onended = () => {
          if (this.running) advanceFile(1, true);
        };
        await source.element.play();
      }
      return source.element;
    }
    const video = document.createElement('video');
    video.srcObject = source.stream;
    video.muted = true;
    video.playsInline = true;
    await video.play();
    this.wrapperVideo = video;
    return video;
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

  /** Swaps the source without dropping the connection -- next photo, or a flipped camera. */
  async replaceSource(source) {
    if (this.stopDrawing) {
      try { this.stopDrawing(); } catch (error) { /* already stopped */ }
      this.stopDrawing = null;
    }
    this.generation++;
    if (this.source && this.source !== source) {
      try { this.source.stop(); } catch (error) { /* already released */ }
    }
    this.source = source;

    closeQuietly(this.videoEncoder);
    this.videoEncoder = null;
    this.stopAudio();

    this.startVideo(source);
    this.startAudio(source);
    ui.liveTitle.textContent = sourceHeadline(source, this.tvName);
  }

  // ---- audio ----------------------------------------------------------

  /** Audio is a bonus, never a requirement: any failure leaves the picture running. */
  startAudio(source) {
    if (typeof window.AudioEncoder === 'undefined') return;
    if (!source.audioTrack && !source.audioNode) return;

    try {
      this.audioEncoder = new AudioEncoder({
        output: (chunk) => {
          const payload = new Uint8Array(chunk.byteLength);
          chunk.copyTo(payload);
          this.send(buildPacket(TYPE.AUDIO, 0, chunk.timestamp, payload));
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
      this.send(buildPacket(TYPE.AUDIO_CONFIG, 0, 0,
        encodeParams({ codec: 'opus', sampleRate: 48000, channels: 2 })));

      if (source.audioTrack && 'MediaStreamTrackProcessor' in window) {
        this.pumpAudioFromTrack(source.audioTrack);
      } else {
        this.pumpAudioViaWebAudio(source);
      }
    } catch (error) {
      this.audioEncoder = null;
    }
  }

  async pumpAudioFromTrack(track) {
    const reader = new MediaStreamTrackProcessor({ track }).readable.getReader();
    try {
      while (this.running && this.audioEncoder) {
        const { value: data, done } = await reader.read();
        if (done) break;
        try {
          if (this.audioEncoder.state === 'configured') this.audioEncoder.encode(data);
        } finally {
          data.close();
        }
      }
    } catch (error) {
      this.audioEncoder = null;
    }
  }

  /**
   * The path for Safari and Firefox, which have no MediaStreamTrackProcessor: pull PCM out of a
   * Web Audio graph and build AudioData by hand. ScriptProcessorNode is deprecated but is the
   * only node available everywhere, and it only runs while connected to a destination -- hence
   * the silent gain node, which keeps it pumping without the phone playing the sound aloud.
   */
  pumpAudioViaWebAudio(source) {
    const AudioCtor = window.AudioContext || window.webkitAudioContext;
    if (typeof AudioCtor !== 'function') return;

    const context = source.audioContext || new AudioCtor();
    this.audioContext = context;
    if (context.state === 'suspended') context.resume().catch(() => {});

    const node = source.audioNode
      || (source.audioTrack ? context.createMediaStreamSource(new MediaStream([source.audioTrack]))
        : null);
    if (!node) return;

    const channels = 2;
    const processor = context.createScriptProcessor(4096, channels, channels);
    const silence = context.createGain();
    silence.gain.value = 0;

    let timestamp = 0;
    processor.onaudioprocess = (event) => {
      if (!this.running || !this.audioEncoder) return;
      const input = event.inputBuffer;
      const frames = input.length;
      const interleaved = new Float32Array(frames * channels);
      for (let channel = 0; channel < channels; channel++) {
        const samples = input.getChannelData(Math.min(channel, input.numberOfChannels - 1));
        for (let i = 0; i < frames; i++) {
          interleaved[i * channels + channel] = samples[i];
        }
      }
      try {
        const data = new AudioData({
          format: 'f32',
          sampleRate: input.sampleRate,
          numberOfFrames: frames,
          numberOfChannels: channels,
          timestamp,
          data: interleaved,
        });
        timestamp += Math.round((frames / input.sampleRate) * 1e6);
        try {
          if (this.audioEncoder.state === 'configured') this.audioEncoder.encode(data);
        } finally {
          data.close();
        }
      } catch (error) {
        this.audioEncoder = null;
      }
    };

    node.connect(processor);
    processor.connect(silence);
    silence.connect(context.destination);
    this.audioProcessor = processor;
  }

  stopAudio() {
    if (this.audioProcessor) {
      try { this.audioProcessor.disconnect(); } catch (error) { /* already gone */ }
      this.audioProcessor.onaudioprocess = null;
      this.audioProcessor = null;
    }
    closeQuietly(this.audioEncoder);
    this.audioEncoder = null;
  }

  // ---- plumbing -------------------------------------------------------

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
    if (this.mode === 'native') {
      ui.liveDetail.textContent = 'Playing on ' + (this.tvName || 'your Fire TV');
      return;
    }
    const fps = this.framesSent;
    const mbps = (this.bytesSent * 8) / 1e6;
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
    this.generation++;

    clearInterval(this.pingTimer);
    clearInterval(this.statsTimer);
    if (this.stopDrawing) {
      try { this.stopDrawing(); } catch (error) { /* already stopped */ }
      this.stopDrawing = null;
    }

    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      try {
        this.socket.send(buildPacket(TYPE.BYE, 0, 0, new Uint8Array(0)));
      } catch (error) { /* closing anyway */ }
    }
    closeQuietly(this.videoEncoder);
    this.videoEncoder = null;
    this.stopAudio();
    if (this.audioContext) {
      this.audioContext.close().catch(() => {});
      this.audioContext = null;
    }
    if (this.wrapperVideo) {
      this.wrapperVideo.srcObject = null;
      this.wrapperVideo = null;
    }
    if (this.source) {
      try { this.source.stop(); } catch (error) { /* already released */ }
      this.source = null;
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

/** Scales to the ceiling, keeping aspect and even dimensions (H.264 requires both). */
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

function isImageFile(file) {
  return (file.type || '').startsWith('image/')
    || /\.(jpe?g|png|gif|webp|heic|heif|bmp)$/i.test(file.name);
}

/** iOS often hands over a file with an empty type, so fall back to the extension. */
function guessMime(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  return {
    mp4: 'video/mp4', m4v: 'video/mp4', mov: 'video/quicktime', mkv: 'video/x-matroska',
    webm: 'video/webm', avi: 'video/x-msvideo', '3gp': 'video/3gpp',
    jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif',
    webp: 'image/webp', heic: 'image/heic', bmp: 'image/bmp',
  }[ext] || 'application/octet-stream';
}

function formatBytes(bytes) {
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
  if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
  return Math.max(1, Math.round(bytes / 1e3)) + ' KB';
}

function formatTime(ms) {
  const total = Math.max(0, Math.round(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return minutes + ':' + String(seconds).padStart(2, '0');
}

/** A name the TV can show, without fingerprinting anything the user did not volunteer. */
function describeThisDevice(source) {
  const agent = navigator.userAgent;
  let platform = 'Computer';
  if (/iPhone/i.test(agent)) platform = 'iPhone';
  else if (/iPad/i.test(agent) || BPlaySources.isApplePortable()) platform = 'iPad';
  else if (/Windows/i.test(agent)) platform = 'Windows PC';
  else if (/Macintosh|Mac OS X/i.test(agent)) platform = 'Mac';
  else if (/CrOS/i.test(agent)) platform = 'Chromebook';
  else if (/Android/i.test(agent)) platform = 'Android device';
  else if (/Linux/i.test(agent)) platform = 'Linux PC';

  const suffix = {
    camera: ' (Camera)',
    image: ' (Photo)',
    'video-file': ' (Video)',
  }[source.kind] || '';
  return platform + suffix;
}

function sourceHeadline(source, tvName) {
  const what = {
    screen: 'Mirroring to ',
    camera: 'Camera on ',
    image: 'Photo on ',
    'video-file': 'Video on ',
  }[source.kind] || 'Sending to ';
  return what + (tvName || 'your Fire TV');
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

let session = null;
let selectedKind = 'screen';
let facingMode = 'environment';
let fileList = [];
let fileIndex = 0;

function showSetupError(message) {
  ui.setupError.textContent = message;
  ui.setupError.hidden = !message;
}

function selectSource(kind) {
  selectedKind = kind;
  const buttons = {
    screen: ui.sourceScreen,
    camera: ui.sourceCamera,
    files: ui.sourceFiles,
  };
  Object.keys(buttons).forEach((key) => {
    const button = buttons[key];
    const active = key === kind;
    button.classList.toggle('selected', active);
    button.setAttribute('aria-checked', active ? 'true' : 'false');
  });
  ui.fileField.hidden = kind !== 'files';
  ui.start.textContent = kind === 'screen' ? 'Start mirroring' : 'Start';

  const hints = {
    screen: 'Everything on this screen goes to the TV.',
    camera: 'Point this device at whatever you want on the TV.',
    files: 'Pick one or more photos, or a video, from this device.',
  };
  ui.sourceHint.textContent = hints[kind] || '';
}

async function acquireSelectedSource() {
  const wantAudio = ui.wantAudio.checked;
  if (selectedKind === 'screen') {
    return BPlaySources.acquireScreen(wantAudio);
  }
  if (selectedKind === 'camera') {
    return BPlaySources.acquireCamera(facingMode, wantAudio);
  }
  fileList = Array.from(ui.files.files || []);
  if (!fileList.length) {
    throw new Error('Choose a photo or video first.');
  }
  fileIndex = 0;
  return BPlaySources.openFile(fileList[fileIndex], wantAudio);
}

async function advanceFile(delta, fromPlaybackEnd) {
  if (!session || !fileList.length) return;
  const next = fileIndex + delta;
  if (next < 0 || next >= fileList.length) {
    if (fromPlaybackEnd) session.stop(null);
    return;
  }
  fileIndex = next;
  updateFilePosition();
  const file = fileList[fileIndex];
  try {
    if (session.mode === 'native') {
      // A fresh offer; the TV restarts its player on the new file.
      session.playFileOnTv(file);
      ui.liveTitle.textContent = (isImageFile(file) ? 'Photo on ' : 'Video on ')
        + (session.tvName || 'your Fire TV');
      ui.transport.hidden = isImageFile(file);
      resetTransport();
    } else {
      const source = await BPlaySources.openFile(file, ui.wantAudio.checked);
      await session.replaceSource(source);
    }
  } catch (error) {
    session.stop(error.message);
  }
}

// ---- transport controls (native playback only) ----------------------------

let scrubbing = false;

function resetTransport() {
  ui.seek.value = '0';
  ui.elapsed.textContent = '0:00';
  ui.duration.textContent = '0:00';
  ui.playPause.textContent = 'Pause';
}

function onPlaybackState(params) {
  const position = parseInt(params.positionMs, 10) || 0;
  const duration = parseInt(params.durationMs, 10) || 0;
  const playing = params.state === 'playing';
  ui.playPause.textContent = playing ? 'Pause' : 'Play';
  ui.elapsed.textContent = formatTime(position);
  ui.duration.textContent = formatTime(duration);
  if (!scrubbing && duration > 0) {
    ui.seek.max = String(duration);
    ui.seek.value = String(Math.min(position, duration));
  }
}

function updateFilePosition() {
  if (!fileList.length) {
    ui.filePosition.textContent = '';
    return;
  }
  ui.filePosition.textContent = (fileIndex + 1) + ' of ' + fileList.length;
  ui.prev.disabled = fileIndex === 0;
  ui.next.disabled = fileIndex === fileList.length - 1;
}

async function flipCamera() {
  if (!session || selectedKind !== 'camera') return;
  facingMode = facingMode === 'environment' ? 'user' : 'environment';
  try {
    const source = await BPlaySources.acquireCamera(facingMode, ui.wantAudio.checked);
    await session.replaceSource(source);
  } catch (error) {
    showSetupError(error.message);
  }
}

function onSessionEnded(reason) {
  session = null;
  ui.panelLive.hidden = true;
  ui.panelSetup.hidden = false;
  ui.start.disabled = false;
  selectSource(selectedKind);
  showSetupError(reason && reason !== 'You stopped sharing' ? reason : '');
}

async function begin() {
  showSetupError('');
  ui.start.disabled = true;

  let source = null;
  let file = null;

  try {
    if (selectedKind === 'files') {
      fileList = Array.from(ui.files.files || []);
      if (!fileList.length) throw new Error('Choose a photo or video first.');
      fileIndex = 0;
      file = fileList[0];
    } else {
      // Acquired before connecting so the permission prompt still counts as a user gesture,
      // which iOS requires for the camera.
      source = await acquireSelectedSource();
    }
  } catch (error) {
    ui.start.disabled = false;
    const aborted = error && (error.name === 'NotAllowedError' || error.name === 'AbortError');
    showSetupError(aborted ? '' : (error.message || String(error)));
    return;
  }

  if (source && source.track) {
    source.track.addEventListener('ended', () => {
      if (session) session.stop('You stopped sharing');
    });
  }

  session = new MirrorSender();
  try {
    await session.start({
      source,
      file,
      pin: ui.pin.value.trim(),
      maxHeight: parseInt(ui.quality.value, 10),
      wantAudio: ui.wantAudio.checked,
    });
    ui.panelSetup.hidden = true;
    ui.panelLive.hidden = false;
    ui.liveTitle.textContent = file
      ? (isImageFile(file) ? 'Photo on ' : 'Video on ') + (session.tvName || 'your Fire TV')
      : sourceHeadline(source, session.tvName);
    if (session.tvName) ui.tvName.textContent = session.tvName;

    ui.fileControls.hidden = selectedKind !== 'files';
    ui.flip.hidden = selectedKind !== 'camera';
    // Transport controls only make sense when the TV is the one playing the file.
    ui.transport.hidden = !(session.mode === 'native' && file && !isImageFile(file));
    updateFilePosition();
  } catch (error) {
    if (session) session.stop(null);
    session = null;
    if (source) {
      try { source.stop(); } catch (stopError) { /* already released */ }
    }
    ui.start.disabled = false;
    showSetupError(error.message || String(error));
  }
}

function init() {
  if (!window.isSecureContext) {
    ui.unsupportedReason.textContent = 'This page was not loaded over HTTPS, so the browser '
      + 'blocks camera and screen access. Open the https:// address shown on the TV.';
    ui.panelUnsupported.hidden = false;
    ui.panelSetup.hidden = true;
    return;
  }
  if (typeof window.VideoEncoder === 'undefined') {
    ui.unsupportedReason.textContent = 'This browser has no WebCodecs video encoder. '
      + 'Chrome 94+, Edge 94+, Safari 16.4+ and Firefox 133+ all have one.';
    ui.panelUnsupported.hidden = false;
    ui.panelSetup.hidden = true;
    return;
  }

  // Screen capture does not exist on iOS in any browser, so do not offer it there.
  const screenAvailable = BPlaySources.canShareScreen();
  if (!screenAvailable) {
    ui.sourceScreen.disabled = true;
    ui.sourceScreen.classList.add('unavailable');
    ui.sourceScreen.querySelector('.source-note').textContent = 'Not possible on iPhone or iPad';
  }
  if (!BPlaySources.canUseCamera()) {
    ui.sourceCamera.disabled = true;
    ui.sourceCamera.classList.add('unavailable');
  }
  selectSource(screenAvailable ? 'screen' : 'camera');

  // The TV's QR code carries the PIN, so a scanned link needs no typing at all.
  const pinFromUrl = new URLSearchParams(location.search).get('pin');
  if (pinFromUrl) ui.pin.value = pinFromUrl.replace(/\D/g, '').slice(0, 4);

  const trackAudio = 'MediaStreamTrackProcessor' in window;
  const webAudio = typeof (window.AudioContext || window.webkitAudioContext) === 'function';
  if (typeof window.AudioEncoder === 'undefined' || (!trackAudio && !webAudio)) {
    ui.wantAudio.checked = false;
    ui.wantAudio.disabled = true;
    ui.audioNote.textContent = '— this browser can\'t send audio';
  } else {
    ui.audioNote.textContent = '— when the source has any';
  }

  ui.sourceScreen.addEventListener('click', () => selectSource('screen'));
  ui.sourceCamera.addEventListener('click', () => selectSource('camera'));
  ui.sourceFiles.addEventListener('click', () => selectSource('files'));
  ui.files.addEventListener('change', () => {
    fileList = Array.from(ui.files.files || []);
    fileIndex = 0;
    selectSource('files');
  });

  ui.start.addEventListener('click', begin);
  ui.stop.addEventListener('click', () => session && session.stop('Stopped'));
  ui.prev.addEventListener('click', () => advanceFile(-1, false));
  ui.next.addEventListener('click', () => advanceFile(1, false));
  ui.flip.addEventListener('click', flipCamera);
  ui.playPause.addEventListener('click', () => {
    if (!session) return;
    const pausing = ui.playPause.textContent === 'Pause';
    session.sendControl(pausing ? 'pause' : 'play', 0);
    ui.playPause.textContent = pausing ? 'Play' : 'Pause';
  });
  // Track the drag locally, and only tell the TV when the thumb is let go -- a seek per pixel
  // would have it thrashing its buffer.
  ui.seek.addEventListener('input', () => {
    scrubbing = true;
    ui.elapsed.textContent = formatTime(parseInt(ui.seek.value, 10) || 0);
  });
  ui.seek.addEventListener('change', () => {
    scrubbing = false;
    if (session) session.sendControl('seek', parseInt(ui.seek.value, 10) || 0);
  });
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
