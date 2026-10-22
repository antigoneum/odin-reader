package io.github.odinreader.input

/** Semantic actions shared by controller input and the reader UI. */
enum class ControllerAction {
    PreviousPage,
    NextPage,
    PreviousChapter,
    NextChapter,
    MoveUp,
    MoveDown,
    MoveLeft,
    MoveRight,
    Confirm,
    Back,
    ToggleControls,
    OpenContents,
    OpenSettings,
}
