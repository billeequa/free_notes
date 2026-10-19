import SwiftUI

extension Color {
    init(hex: UInt32) { self.init(.sRGB, red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, opacity: 1) }
}
struct Palette {
    let background: Color, surface: Color, primary: Color, text: Color, secondary: Color, chrome: Color
    let dark: Bool
    static let themes: [(String, String)] = [("light","Paper"),("light_2","Sunshine"),("light_3","Sage"),("dark_1","Forest"),("dark_2","Charcoal"),("dark_3","Midnight"),("snow","Snow"),("rose","Rose"),("lavender","Lavender"),("amoled","OLED black")]
    init(_ name: String) {
        let values: (UInt32, UInt32, UInt32, UInt32, UInt32, UInt32, Bool)
        switch name {
        case "light": values = (0xFBF5EC,0xFFFAF2,0x5D7C70,0x27231D,0x766D60,0xFFFAF2,false)
        case "light_2": values = (0xF4EFE6,0xF4EFE6,0x6EC1E4,0x2D2A24,0x70685D,0xF6C23E,false)
        case "light_3": values = (0xF7F2EA,0xFFFAF4,0x4F7A71,0x2A2621,0x756A5C,0xDCE8E1,false)
        case "dark_2": values = (0x242824,0x343933,0x8BC8B2,0xE7DDD0,0xD5CCBE,0x343933,true)
        case "dark_3": values = (0x10151C,0x18212B,0x7CC7D9,0xEAF0F4,0xB8C3CC,0x213040,true)
        case "snow": values = (0xFFFFFF,0xFFFFFF,0x315DA8,0x1B1B1F,0x66666A,0xFFFFFF,false)
        case "rose": values = (0xFFF4F5,0xFFF4F5,0x98455E,0x27231D,0x766D60,0xFFF4F5,false)
        case "lavender": values = (0xF6F2FF,0xF6F2FF,0x66509B,0x27231D,0x766D60,0xF6F2FF,false)
        case "amoled": values = (0,0,0xB9C5FF,0xE6E1E5,0xCAC4D0,0,true)
        default: values = (0x151816,0x1D211E,0x8BC8B2,0xF1EFE8,0xD5CCBE,0x1D211E,true)
        }
        background = Color(hex: values.0); surface = Color(hex: values.1); primary = Color(hex: values.2)
        text = Color(hex: values.3); secondary = Color(hex: values.4); chrome = Color(hex: values.5); dark = values.6
    }
    func taskColor(_ flags: [String], done: Bool) -> Color {
        let mask = (flags.contains("urgent") ? 1 : 0) | (flags.contains("important") ? 2 : 0) | (flags.contains("long_term") ? 4 : 0)
        let light: [UInt32] = [0xE6E2DA,0xFFD9DE,0xFFEDAB,0xFFDDC0,0xD9E8FF,0xECDCF8,0xC8EFE9,0xDDEFCA]
        let night: [UInt32] = [0x343A35,0x672D35,0x574A1B,0x683C20,0x25456D,0x513466,0x20534F,0x36522C]
        let base = Color(hex: dark ? night[mask] : light[mask])
        // Place a translucent neutral surface over the completed card in the view.
        return base
    }
}
struct Card: ViewModifier {
    let color: Color
    func body(content: Content) -> some View { content.padding(14).frame(maxWidth: .infinity, alignment: .leading).background(color, in: RoundedRectangle(cornerRadius: 12)) }
}
extension View { func card(_ color: Color) -> some View { modifier(Card(color: color)) } }
