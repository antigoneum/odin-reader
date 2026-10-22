package io.github.odinreader.input

/** Semantic actions shared by controller input and the reader UI. */
enum class ControllerAction(val label: String) {
    PreviousPage("上一页"),
    NextPage("下一页"),
    PreviousChapter("上一章"),
    NextChapter("下一章"),
    MoveUp("向上移动"),
    MoveDown("向下移动"),
    MoveLeft("向左移动"),
    MoveRight("向右移动"),
    Confirm("确认 / 显示控制栏"),
    Back("返回"),
    ToggleControls("显示 / 隐藏控制栏"),
    OpenContents("章节目录"),
    OpenSettings("设置"),
}
