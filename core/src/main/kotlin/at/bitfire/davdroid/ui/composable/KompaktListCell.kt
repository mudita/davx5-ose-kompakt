/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import at.bitfire.davdroid.R
import at.bitfire.davdroid.ui.KompaktTypography500
import at.bitfire.davdroid.ui.KompaktTypography900
import com.mudita.mmd.components.text.TextMMD

/**
 * A Kompakt list row: a 28dp [leading] slot, a title ((NEW) Label/Medium/900) and subtitle
 * ((NEW) Label/Small/500), with an optional [trailing] slot (e.g. a switch) and an optional bottom
 * separator.
 */
@Composable
fun KompaktListCell(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
    showDivider: Boolean = false
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp)
            ) {
                leading()
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                TextMMD(text = title, style = KompaktTypography900.labelMedium)
                TextMMD(text = subtitle, style = KompaktTypography500.labelSmall)
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(16.dp))
                trailing()
            }
        }

        if (showDivider)
            KompaktDottedDivider(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // starts where the title does: row padding + icon slot + gap
                    .padding(start = 16.dp + 28.dp + 16.dp)
                    .fillMaxWidth()
            )
    }
}

@Preview
@Composable
private fun KompaktListCell_Preview() {
    KompaktListCell(
        title = "Calendar",
        subtitle = "Last sync - Today 11:30",
        leading = {
            Icon(
                painter = painterResource(R.drawable.ic_kompakt_success),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
        },
        showDivider = true
    )
}
