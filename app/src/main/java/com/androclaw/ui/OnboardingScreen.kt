package com.androclaw.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androclaw.ui.components.AppIcon

data class FeatureItem(
    val icon: String,
    val text: String
)

data class OnboardingPage(
    val emoji: String? = null,
    val useAppIcon: Boolean = false,
    val title: String,
    val subtitle: String,
    val features: List<FeatureItem> = emptyList(),
    val quotes: List<String> = emptyList(),
    val buttonText: String = "Continue",
    val isPermissionPage: Boolean = false,
    val permissionLabel: String? = null
)

val onboardingPages = listOf(
    OnboardingPage(
        useAppIcon = true,
        title = "Meet AndroClaw",
        subtitle = "Your personal AI agent that lives on your phone. Just tell it what to do in plain English.",
        features = listOf(
            FeatureItem("\uD83D\uDCDE", "Call & message anyone"),
            FeatureItem("\uD83C\uDF10", "Browse & search the web"),
            FeatureItem("\u2699\uFE0F", "Control device settings"),
            FeatureItem("\uD83D\uDCF1", "Works across all your apps")
        ),
        buttonText = "Get Started"
    ),
    OnboardingPage(
        emoji = "\uD83D\uDD10",
        title = "Core Permissions",
        subtitle = "AndroClaw needs access to calls, messages, contacts, and calendar to act on your behalf. Nothing is shared externally.",
        isPermissionPage = true,
        permissionLabel = "core",
        buttonText = "Grant Access"
    ),
    OnboardingPage(
        emoji = "\uD83D\uDCC2",
        title = "Files & Media",
        subtitle = "Let AndroClaw find, open, and share your files and photos. It can locate any file on your device instantly.",
        isPermissionPage = true,
        permissionLabel = "storage",
        buttonText = "Allow File Access"
    ),
    OnboardingPage(
        emoji = "\uD83D\uDCAC",
        title = "Floating Assistant",
        subtitle = "A small floating button stays on screen so you can summon AndroClaw from any app — anytime, anywhere.",
        isPermissionPage = true,
        permissionLabel = "overlay",
        buttonText = "Enable Overlay"
    ),
    OnboardingPage(
        useAppIcon = true,
        title = "You're All Set",
        subtitle = "Add your Claude API key in Settings and start commanding your phone with natural language.",
        quotes = listOf(
            "Call Mom",
            "Turn off WiFi and lower brightness",
            "Scroll through Reels for 5 minutes",
            "Find my resume and share it on WhatsApp"
        ),
        buttonText = "Open AndroClaw"
    )
)

@Composable
fun OnboardingScreen(
    currentPage: Int,
    onNextPage: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    val page = onboardingPages[currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.15f))

            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    (slideInHorizontally { it / 2 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally { -it / 2 } + fadeOut(tween(200)))
                },
                label = "page"
            ) { pageIndex ->
                val p = onboardingPages[pageIndex]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Icon / Illustration
                    if (p.useAppIcon) {
                        AppIcon(size = 120.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (p.emoji != null) {
                                Text(text = p.emoji, fontSize = 52.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = p.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = p.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )

                    if (p.features.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 2-column grid
                            p.features.chunked(2).forEach { row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Max),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    row.forEach { item ->
                                        FeatureCard(
                                            icon = item.icon,
                                            text = item.text,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // Fill empty space if odd number
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (p.quotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            p.quotes.forEach { quote ->
                                QuoteChip(quote)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.3f))

            PageIndicator(
                totalPages = onboardingPages.size,
                currentPage = currentPage
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (page.isPermissionPage && page.permissionLabel != null) {
                        onRequestPermission(page.permissionLabel)
                    } else if (currentPage == onboardingPages.lastIndex) {
                        onFinish()
                    } else {
                        onNextPage()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = page.buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            if (page.isPermissionPage) {
                TextButton(
                    onClick = onNextPage,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "Skip for now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FeatureCard(icon: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 28.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuoteChip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\u201C",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageIndicator(totalPages: Int, currentPage: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalPages) { index ->
            val isActive = index == currentPage
            val width by animateDpAsState(
                targetValue = if (isActive) 24.dp else 8.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "indicator_width"
            )
            val alpha by animateFloatAsState(
                targetValue = if (isActive) 1f else 0.3f,
                label = "indicator_alpha"
            )

            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
