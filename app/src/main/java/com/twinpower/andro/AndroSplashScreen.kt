
package com.twinpower.andro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AndroSplashScreen(
    modifier: Modifier = Modifier,
    darkBackground: Boolean = true
) {
    val backgroundColor = if (darkBackground) {
        Color.Black
    } else {
        Color.White
    }

    val foregroundColor = if (darkBackground) {
        Color.White
    } else {
        Color.Black
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(foregroundColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "A",
                    color = backgroundColor,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 68.sp
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Andro",
                color = foregroundColor,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "BUILD · CONNECT · CREATE",
                color = foregroundColor.copy(alpha = 0.65f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )
        }

        Text(
            text = "A development workspace",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .let { it },
            color = foregroundColor.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
