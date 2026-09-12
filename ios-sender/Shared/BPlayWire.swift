import Foundation

/// The BPlay wire format, in Swift.
///
/// Third implementation of `protocol/src/main/java/com/bplay/protocol` (after Java and the
/// browser's `wire.js`). All three must agree byte for byte; `tools/wire-vectors.txt` holds the
/// shared conformance vectors, and `BPlayWireTests.swift` checks this one against them.
enum BPlayWire {

    static let magic: [UInt8] = [0x42, 0x50, 0x4C, 0x59] // "BPLY"
    static let version: UInt8 = 1
    static let headerSize = 14

    enum PacketType: UInt8 {
        case videoConfig = 1
        case video = 2
        case audioConfig = 3
        case audio = 4
        case ping = 5
        case bye = 6
        case meta = 7
    }

    static let flagKeyframe: UInt8 = 0x01

    enum Status: UInt8 {
        case ok = 0
        case badPin = 1
        case busy = 2
        case badVersion = 3

        var message: String {
            switch self {
            case .ok: return "Connected"
            case .badPin: return "Wrong PIN. Check the four digits on the TV."
            case .busy: return "Another device is already mirroring to that TV."
            case .badVersion: return "The TV runs a different version of BPlay."
            }
        }
    }

    // MARK: - Params

    private static func escape(_ text: String) -> String {
        var out = ""
        for character in text {
            switch character {
            case "\\", "=": out.append("\\"); out.append(character)
            case "\n": out.append("\\n")
            default: out.append(character)
            }
        }
        return out
    }

    static func encodeParams(_ fields: [(String, String)]) -> Data {
        let line = fields.map { escape($0.0) + "=" + escape($0.1) }.joined(separator: "\n")
        return Data(line.utf8)
    }

    static func decodeParams(_ data: Data) -> [String: String] {
        guard let text = String(data: data, encoding: .utf8) else { return [:] }
        var out: [String: String] = [:]
        var key: String?
        var token = ""
        var escaped = false
        for character in text {
            if escaped {
                token.append(character == "n" ? "\n" : character)
                escaped = false
            } else if character == "\\" {
                escaped = true
            } else if character == "=" && key == nil {
                key = token
                token = ""
            } else if character == "\n" {
                if let k = key { out[k] = token }
                key = nil
                token = ""
            } else {
                token.append(character)
            }
        }
        if let k = key { out[k] = token }
        return out
    }

    // MARK: - Handshake

    static func handshake(pin: String, name: String, width: Int, height: Int,
                          audio: Bool) -> Data {
        var fields: [(String, String)] = [
            ("pin", pin),
            ("name", name),
            ("platform", "ios"),
            ("width", String(width)),
            ("height", String(height)),
            ("fps", "30"),
            ("vcodec", "video/avc"),
        ]
        if audio {
            fields.append(("acodec", "audio/mp4a-latm"))
            fields.append(("asamplerate", "44100"))
            fields.append(("achannels", "2"))
        }
        let body = encodeParams(fields)
        var out = Data(magic)
        out.append(version)
        out.append(UInt8((body.count >> 8) & 0xFF))
        out.append(UInt8(body.count & 0xFF))
        out.append(body)
        return out
    }

    struct HandshakeResponse {
        let status: Status
        let params: [String: String]

        var error: String {
            params["error"] ?? status.message
        }
    }

    static func parseHandshakeResponse(_ data: Data) -> HandshakeResponse? {
        guard data.count >= 3 else { return nil }
        let bytes = [UInt8](data)
        let length = Int(bytes[1]) << 8 | Int(bytes[2])
        guard data.count >= 3 + length else { return nil }
        let status = Status(rawValue: bytes[0]) ?? .badVersion
        let params = decodeParams(data.subdata(in: 3..<(3 + length)))
        return HandshakeResponse(status: status, params: params)
    }

    // MARK: - Packets

    static func packet(_ type: PacketType, flags: UInt8 = 0, timestampUs: Int64 = 0,
                       payload: Data = Data()) -> Data {
        var out = Data(capacity: headerSize + payload.count)
        out.append(type.rawValue)
        out.append(flags)
        let length = UInt32(payload.count)
        out.append(UInt8((length >> 24) & 0xFF))
        out.append(UInt8((length >> 16) & 0xFF))
        out.append(UInt8((length >> 8) & 0xFF))
        out.append(UInt8(length & 0xFF))
        let pts = UInt64(max(0, timestampUs))
        for shift in stride(from: 56, through: 0, by: -8) {
            out.append(UInt8((pts >> UInt64(shift)) & 0xFF))
        }
        out.append(payload)
        return out
    }
}
