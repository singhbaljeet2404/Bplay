import Foundation
import Network

/// Talks to the Fire TV: opens the TCP stream, performs the handshake, then writes packets.
///
/// Built on `Network.framework` rather than raw sockets because a broadcast extension runs under a
/// hard memory limit (50 MB) and gets suspended aggressively; `NWConnection` handles the socket
/// lifecycle and backpressure without a thread of its own.
final class MirrorClient {

    enum ClientError: LocalizedError {
        case refused(String)
        case notConnected
        case timedOut

        var errorDescription: String? {
            switch self {
            case .refused(let reason): return reason
            case .notConnected: return "Not connected to the TV."
            case .timedOut: return "The TV did not answer. Check you are on the same Wi-Fi."
            }
        }
    }

    private let queue = DispatchQueue(label: "com.bplay.sender.connection")
    private var connection: NWConnection?
    private(set) var tvName = "Fire TV"
    private(set) var maxHeight = 1080
    private(set) var maxBitrate = 8_000_000

    /// Bytes queued in the socket. Used to drop frames rather than let latency accumulate.
    private var pendingBytes = 0
    private let pendingLimit = 4 * 1024 * 1024

    func connect(host: String, port: UInt16, pin: String, deviceName: String,
                 width: Int, height: Int, audio: Bool,
                 completion: @escaping (Result<Void, Error>) -> Void) {
        let endpoint = NWEndpoint.Host(host)
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            completion(.failure(ClientError.refused("Invalid port")))
            return
        }

        let parameters = NWParameters.tcp
        if let tcp = parameters.defaultProtocolStack.internetProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true            // a frame per packet; batching would only add latency
            tcp.connectionTimeout = 6
            tcp.enableKeepalive = true
        }

        let connection = NWConnection(host: endpoint, port: nwPort, using: parameters)
        self.connection = connection

        var finished = false
        let finish: (Result<Void, Error>) -> Void = { result in
            guard !finished else { return }
            finished = true
            completion(result)
        }

        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                self.sendHandshake(pin: pin, deviceName: deviceName, width: width,
                                   height: height, audio: audio, completion: finish)
            case .failed(let error):
                finish(.failure(error))
            case .cancelled:
                finish(.failure(ClientError.notConnected))
            default:
                break
            }
        }
        connection.start(queue: queue)

        queue.asyncAfter(deadline: .now() + 10) {
            finish(.failure(ClientError.timedOut))
        }
    }

    private func sendHandshake(pin: String, deviceName: String, width: Int, height: Int,
                               audio: Bool,
                               completion: @escaping (Result<Void, Error>) -> Void) {
        guard let connection = connection else {
            completion(.failure(ClientError.notConnected))
            return
        }
        let request = BPlayWire.handshake(pin: pin, name: deviceName, width: width,
                                          height: height, audio: audio)
        connection.send(content: request, completion: .contentProcessed { error in
            if let error = error {
                completion(.failure(error))
                return
            }
            // The reply is three bytes plus a short body, so one receive covers it.
            connection.receive(minimumIncompleteLength: 3, maximumLength: 4096) {
                data, _, _, error in
                if let error = error {
                    completion(.failure(error))
                    return
                }
                guard let data = data,
                      let response = BPlayWire.parseHandshakeResponse(data) else {
                    completion(.failure(ClientError.refused("The TV sent an unreadable reply.")))
                    return
                }
                guard response.status == .ok else {
                    completion(.failure(ClientError.refused(response.error)))
                    return
                }
                self.tvName = response.params["name"] ?? self.tvName
                self.maxHeight = Int(response.params["maxHeight"] ?? "") ?? self.maxHeight
                self.maxBitrate = Int(response.params["maxBitrate"] ?? "") ?? self.maxBitrate
                completion(.success(()))
            }
        })
    }

    /// - Returns: false when the packet was dropped because the socket is already backed up.
    @discardableResult
    func send(_ data: Data) -> Bool {
        guard let connection = connection, connection.state == .ready else { return false }
        var dropped = false
        queue.sync {
            if pendingBytes > pendingLimit {
                dropped = true
            } else {
                pendingBytes += data.count
            }
        }
        if dropped { return false }

        connection.send(content: data, completion: .contentProcessed { [weak self] _ in
            self?.queue.async {
                self?.pendingBytes -= data.count
            }
        })
        return true
    }

    func close() {
        _ = send(BPlayWire.packet(.bye))
        connection?.cancel()
        connection = nil
    }
}
