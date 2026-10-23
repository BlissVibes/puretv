package com.puretv.twitch.desktop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * The type system: Google Sans Flex, the Material 3 Expressive typeface (OFL), bundled
 * from the classpath (`src/main/resources/fonts/`, the same place as `icon.ico`).
 *
 *  - PureTV Sans Display: headings, mastheads, channel names (optical size 40)
 *  - PureTV Sans:         every other string, data included (optical size 18)
 *
 * Google Sans Flex ships as one variable font, and Compose 1.7's desktop
 * `Font(resource, ...)` cannot pick variation axes (every weight would render at the
 * default instance), so static weights were baked from it with
 * `fontTools.varLib.instancer` (wght per file; opsz as above; wdth 100, ROND 0, slnt 0).
 * They are renamed "PureTV Sans" inside the files, as Google's trademark note asks of
 * modified versions; the copyright and licence notices are kept, and the licence ships
 * as `fonts/OFL-PureTvSans.txt`.
 */

val PureTvSansDisplay: FontFamily = FontFamily(
    Font("fonts/PureTvSansDisplay-Medium.ttf", FontWeight.Medium),
    Font("fonts/PureTvSansDisplay-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/PureTvSansDisplay-Bold.ttf", FontWeight.Bold),
    Font("fonts/PureTvSansDisplay-ExtraBold.ttf", FontWeight.ExtraBold),
)

val PureTvSans: FontFamily = FontFamily(
    Font("fonts/PureTvSans-Light.ttf", FontWeight.Light),
    Font("fonts/PureTvSans-Regular.ttf", FontWeight.Normal),
    Font("fonts/PureTvSans-Medium.ttf", FontWeight.Medium),
    Font("fonts/PureTvSans-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/PureTvSans-Bold.ttf", FontWeight.Bold),
)
