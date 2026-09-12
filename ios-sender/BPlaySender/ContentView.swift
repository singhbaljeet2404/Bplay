import SwiftUI
import ReplayKit

/// The container app. It does not mirror anything itself -- it only stores the TV's address and
/// PIN where the broadcast extension can read them, and puts the system's broadcast picker on
/// screen. All the actual work happens in BPlayBroadcast/SampleHandler.swift.
struct ContentView: View {

    @AppStorage("host", store: UserDefaults(suiteName: "group.com.bplay.sender"))
    private var host: String = ""

    @AppStorage("pin", store: UserDefaults(suiteName: "group.com.bplay.sender"))
    private var pin: String = ""

    @AppStorage("port", store: UserDefaults(suiteName: "group.com.bplay.sender"))
    private var port: Int = 7100

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("192.168.1.42", text: $host)
                        .keyboardType(.decimalPad)
                        .autocorrectionDisabled()
                    TextField("PIN", text: $pin)
                        .keyboardType(.numberPad)
                } header: {
                    Text("Your Fire TV")
                } footer: {
                    Text("Both are shown on the BPlay screen on your TV. Leave the PIN empty if "
                         + "the TV says the PIN is off.")
                }

                Section {
                    BroadcastPicker()
                        .frame(height: 60)
                } header: {
                    Text("Start")
                } footer: {
                    Text("Tap the button, choose BPlay, then Start Broadcast. To stop, tap the "
                         + "red status bar at the top of the screen.\n\n"
                         + "You can also start it from Control Centre: press and hold the screen "
                         + "recording button and pick BPlay.")
                }

                Section {
                    Label("Your iPhone and the TV must be on the same Wi-Fi network.",
                          systemImage: "wifi")
                    Label("Video travels over Wi-Fi. Bluetooth is far too slow to carry a screen.",
                          systemImage: "antenna.radiowaves.left.and.right")
                    Label("Nothing leaves your home network. There is no account and no cloud.",
                          systemImage: "lock")
                } header: {
                    Text("Good to know")
                }
            }
            .navigationTitle("BPlay Sender")
        }
        .navigationViewStyle(.stack)
    }
}

/// UIKit's broadcast picker; SwiftUI has no equivalent.
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(
            frame: CGRect(x: 0, y: 0, width: 200, height: 60))
        // Naming the extension keeps the sheet to a single entry instead of listing every
        // screen-recording app on the device.
        picker.preferredExtension = "com.bplay.sender.broadcast"
        picker.showsMicrophoneButton = false
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) { }
}
