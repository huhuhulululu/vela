import Foundation

/// 无修饰键快捷键（M、F、I、空格、J、K、方向键）何时可以触发：
/// 年龄确认之前、有弹层（筛选、收藏夹、口味、确认页等）、或正在文本框里输入时一律不触发，
/// 字符归输入框。键盘监视器与菜单等效键共用这一条规则，不各写一份。
public enum ShortcutGate {
    public static func allowsPlainKeys(ageConfirmed: Bool, interacting: Bool, editingText: Bool) -> Bool {
        ageConfirmed && !interacting && !editingText
    }
}
