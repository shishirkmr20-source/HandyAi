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
package com.handyai.data.model

/**
 * Standardized habit categories and statuses.
 *
 * The category list is based on the consolidated lists used by major
 * habit-tracking apps (Habitica, Strides, Productive, HabitNow) and
 * productivity literature (Atomic Habits, The Power of Habit). The
 * categories cover the most common dimensions users organize habits
 * along, without being so granular that the dropdown becomes
 * overwhelming.
 *
 * The status list covers the standard lifecycle states a habit moves
 * through: Active → Paused → Completed (or Archived). This matches
 * what Habitica / Strides / Productive all use, just with simpler
 * labels.
 */
object HabitCategories {

    /** Standard dropdown values for the category field. */
    val STANDARD_VALUES = listOf(
        "Health & Fitness",
        "Nutrition & Diet",
        "Sleep & Rest",
        "Mindfulness & Mental Health",
        "Productivity & Time Management",
        "Personal Development",
        "Education & Learning",
        "Career & Work",
        "Finance & Money",
        "Relationships & Social",
        "Creativity & Hobbies",
        "Spirituality",
        "Environment & Home",
        "Other"
    )

    /** Default value when none is selected. */
    const val DEFAULT = "Health & Fitness"
}

object HabitStatus {

    /** Standard dropdown values for the status field. */
    val STANDARD_VALUES = listOf(
        "Active",     // user is currently working on this habit
        "Paused",     // temporarily not tracking; will resume later
        "Completed",  // goal reached; no longer needs tracking
        "Archived"    // done with it, hide from main list
    )

    /** Default value for newly created habits. */
    const val DEFAULT = "Active"
}
