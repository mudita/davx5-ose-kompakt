/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mudita.mmd.R as MmdR

val LatoFontFamily = FontFamily(
    Font(MmdR.font.lato_regular, weight = FontWeight.Normal),
    Font(MmdR.font.lato_semi_bold, weight = FontWeight.Medium),
    Font(MmdR.font.lato_black, weight = FontWeight.Black),
)

object KompaktTypography900 {
    val titleMedium = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 25.sp,
        lineHeight = 28.sp
    )
    val labelLarge = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 23.sp,
        lineHeight = 23.sp
    )
    val labelMedium = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp,
        lineHeight = 20.sp
    )
    val labelSmall = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp,
        lineHeight = 16.sp
    )
}

object KompaktTypography500 {
    val bodyMedium = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 25.sp
    )
    val bodySmall = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp
    )
    val labelMedium = TextStyle(
        fontFamily = LatoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 23.sp
    )
}
