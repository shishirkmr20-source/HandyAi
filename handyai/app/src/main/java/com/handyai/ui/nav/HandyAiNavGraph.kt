/*
 * HandyAi — on-device AI chat for Android.
 * Copyright 2026 HandyAi Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.handyai.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.handyai.ui.screens.HabitTrackerScreen
import com.handyai.ui.screens.JournalScreen
import com.handyai.ui.screens.MainScreen
import com.handyai.ui.screens.ModelSettingsScreen
import com.handyai.ui.screens.SettingsScreen

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val JOURNAL = "journal"
    const val HABITS = "habits"
}

@Composable
fun HandyAiNavGraph() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.MAIN) {

        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenModels = { nav.navigate(Routes.MODELS) },
                onOpenJournal = { nav.navigate(Routes.JOURNAL) },
                onOpenHabits = { nav.navigate(Routes.HABITS) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.MODELS) {
            ModelSettingsScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.JOURNAL) {
            JournalScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.HABITS) {
            HabitTrackerScreen(onBack = { nav.popBackStack() })
        }
    }
}
