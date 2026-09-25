package dev.mithril.mithrilpf.ui

data class PanelLayout(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun fit(screenWidth: Int, screenHeight: Int): PanelLayout {
            val width = (screenWidth - 16).coerceIn(1, 320)
            val height = (screenHeight - 16).coerceIn(1, 212)
            return PanelLayout(
                (screenWidth - width) / 2,
                (screenHeight - height) / 2,
                width,
                height,
            )
        }
    }
}
