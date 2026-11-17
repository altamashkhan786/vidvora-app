package com.example.ui.navigation

import com.example.data.model.VideoToken

sealed interface AppScreen {
  data object Home : AppScreen
  data class Player(val video: VideoToken) : AppScreen
  data object Admin : AppScreen
}
