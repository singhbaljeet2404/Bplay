import ReplayKit
import VideoToolbox
import CoreMedia

/// The ReplayKit broadcast extension: where an iPhone or iPad's screen actually gets encoded.
///
/// iOS gives no other route. Safari has no `getDisplayMedia`, and a normal app cannot read the
/// screen outside its own window, so full-device mirroring has to run in a Broadcast Upload
/// Extension started from Control Centre.
///
/// The extension is memory-capped at 50 MB and killed without ceremony if it exceeds that, which
/// shapes everything here: encode in place, never buffer frames, and hand bytes to the socket as
/// soon as VideoToolbox produces them.
class SampleHandler: RPBroadcastSampleHandler {

    private let client = MirrorClient()
    private var compressionSession: VTCompressionSession?
    private var started = false
    private var frameIndex: Int64 = 0
    private var lastKeyframe = CFAbsoluteTimeGetCurrent()

    /// Shared with the container app, which is where the user types the TV address and PIN.
    private var settings: UserDefaults? {
        UserDefaults(suiteName: "group.com.bplay.sender")
    }

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let defaults = settings
        let host = setupInfo?["host"] as? String
            ?? defaults?.string(forKey: "host") ?? ""
        let pin = setupInfo?["pin"] as? String
            ?? defaults?.string(forKey: "pin") ?? ""
        let port = UInt16(defaults?.integer(forKey: "port") ?? 0)

        guard !host.isEmpty else {
            finish("Open BPlay Sender first and enter your Fire TV's address.")
            return
        }

        let screen = UIScreen.main.bounds
        let scale = UIScreen.main.scale
        let width = Int(screen.width * scale)
        let height = Int(screen.height * scale)

        let semaphore = DispatchSemaphore(value: 0)
        var connectError: Error?
        client.connect(host: host, port: port == 0 ? 7100 : port, pin: pin,
                       deviceName: UIDevice.current.name,
                       width: width, height: height, audio: true) { result in
            if case .failure(let error) = result { connectError = error }
            semaphore.signal()
        }
        // ReplayKit expects broadcastStarted to have set things up before frames arrive, so this
        // one place blocks; everything afterwards is asynchronous.
        _ = semaphore.wait(timeout: .now() + 12)

        if let error = connectError {
            finish(error.localizedDescription)
            return
        }
        started = true
    }

    override func broadcastPaused() { }

    override func broadcastResumed() {
        // Force a keyframe: the TV has been staring at a frozen picture.
        lastKeyframe = .zero
    }

    override func broadcastFinished() {
        if let session = compressionSession {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        compressionSession = nil
        client.close()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                      with sampleBufferType: RPSampleBufferType) {
        guard started else { return }
        switch sampleBufferType {
        case .video:
            encodeVideo(sampleBuffer)
        case .audioApp:
            // App audio only. Microphone samples arrive as .audioMic and are deliberately ignored:
            // mirroring a screen should not quietly broadcast the room.
            break
        default:
            break
        }
    }

    // MARK: - Video

    private func encodeVideo(_ sampleBuffer: CMSampleBuffer) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)

        if compressionSession == nil {
            setUpCompression(width: width, height: height)
        }
        guard let session = compressionSession else { return }

        let now = CFAbsoluteTimeGetCurrent()
        let forceKeyframe = now - lastKeyframe >= 1.0
        if forceKeyframe { lastKeyframe = now }

        let properties: [CFString: Any]? = forceKeyframe
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true]
            : nil

        frameIndex += 1
        let presentationTime = CMTime(value: frameIndex, timescale: 30)
        VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: imageBuffer,
            presentationTimeStamp: presentationTime,
            duration: .invalid,
            frameProperties: properties as CFDictionary?,
            sourceFrameRefcon: nil,
            infoFlagsOut: nil)
    }

    private func setUpCompression(width: Int, height: Int) {
        // Scale down to the TV's ceiling, keeping the aspect and both sides even.
        let limit = client.maxHeight
        var targetWidth = width
        var targetHeight = height
        let shortest = min(width, height)
        if shortest > limit {
            let scale = Double(limit) / Double(shortest)
            targetWidth = Int((Double(width) * scale / 2).rounded()) * 2
            targetHeight = Int((Double(height) * scale / 2).rounded()) * 2
        }

        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(targetWidth),
            height: Int32(targetHeight),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: compressionOutput,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &session)

        guard status == noErr, let created = session else {
            finish("This device could not start its video encoder.")
            return
        }

        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_RealTime,
                             value: kCFBooleanTrue)
        // Constrained Baseline: every Fire TV decodes it in hardware, including the old sticks.
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_ProfileLevel,
                             value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AllowFrameReordering,
                             value: kCFBooleanFalse) // B-frames would add a frame of latency
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_MaxKeyFrameInterval,
                             value: NSNumber(value: 30))
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_ExpectedFrameRate,
                             value: NSNumber(value: 30))
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AverageBitRate,
                             value: NSNumber(value: min(bitrate(for: targetHeight),
                                                        client.maxBitrate)))
        VTCompressionSessionPrepareToEncodeFrames(created)
        compressionSession = created

        _ = client.send(BPlayWire.packet(.meta, payload: BPlayWire.encodeParams([
            ("width", String(targetWidth)),
            ("height", String(targetHeight)),
        ])))
    }

    private func bitrate(for height: Int) -> Int {
        if height <= 480 { return 2_000_000 }
        if height <= 720 { return 4_000_000 }
        return 8_000_000
    }

    fileprivate func handleEncoded(_ sampleBuffer: CMSampleBuffer) {
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }

        let isKeyframe = Self.isKeyframe(sampleBuffer)
        var annexB = Data()

        // VideoToolbox emits AVCC: each NAL prefixed with its length. The Fire TV's MediaCodec
        // wants Annex-B start codes, and parameter sets live outside the frame data entirely, so
        // both need converting and the parameter sets need prepending to every keyframe.
        if isKeyframe, let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            annexB.append(Self.parameterSets(from: format))
        }

        var lengthAtOffset = 0
        var totalLength = 0
        var pointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &lengthAtOffset,
                                          totalLengthOut: &totalLength,
                                          dataPointerOut: &pointer) == noErr,
              let base = pointer else { return }

        let startCode: [UInt8] = [0, 0, 0, 1]
        var offset = 0
        while offset < totalLength - 4 {
            var nalLength: UInt32 = 0
            memcpy(&nalLength, base + offset, 4)
            nalLength = CFSwapInt32BigToHost(nalLength)
            guard nalLength > 0, offset + 4 + Int(nalLength) <= totalLength else { break }
            annexB.append(contentsOf: startCode)
            annexB.append(UnsafeBufferPointer(
                start: UnsafeRawPointer(base + offset + 4).assumingMemoryBound(to: UInt8.self),
                count: Int(nalLength)))
            offset += 4 + Int(nalLength)
        }

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let microseconds = Int64(CMTimeGetSeconds(timestamp) * 1_000_000)
        _ = client.send(BPlayWire.packet(.video,
                                         flags: isKeyframe ? BPlayWire.flagKeyframe : 0,
                                         timestampUs: microseconds,
                                         payload: annexB))
    }

    private static func isKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first else {
            return true // no attachment means "not a dependent frame"
        }
        return !(first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
    }

    private static func parameterSets(from format: CMFormatDescription) -> Data {
        var out = Data()
        var count = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: 0, parameterSetPointerOut: nil,
            parameterSetSizeOut: nil, parameterSetCountOut: &count,
            nalUnitHeaderLengthOut: nil) == noErr else { return out }

        for index in 0..<count {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size, parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil) == noErr, let bytes = pointer {
                out.append(contentsOf: [0, 0, 0, 1])
                out.append(UnsafeBufferPointer(start: bytes, count: size))
            }
        }
        return out
    }

    private func finish(_ message: String) {
        let error = NSError(domain: "com.bplay.sender", code: 1,
                            userInfo: [NSLocalizedDescriptionKey: message])
        finishBroadcastWithError(error)
    }
}

/// VideoToolbox hands finished frames back here, on its own thread.
private func compressionOutput(outputCallbackRefCon: UnsafeMutableRawPointer?,
                               sourceFrameRefCon: UnsafeMutableRawPointer?,
                               status: OSStatus,
                               infoFlags: VTEncodeInfoFlags,
                               sampleBuffer: CMSampleBuffer?) {
    guard status == noErr,
          let sampleBuffer = sampleBuffer,
          CMSampleBufferDataIsReady(sampleBuffer),
          let refcon = outputCallbackRefCon else { return }
    let handler = Unmanaged<SampleHandler>.fromOpaque(refcon).takeUnretainedValue()
    handler.handleEncoded(sampleBuffer)
}
