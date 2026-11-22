package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

data class PlanPrice(val price: String, val period: String, val note: String? = null)

@Composable
fun PlanCard(
    name: String,
    pitch: String,
    monthly: PlanPrice,
    annual: PlanPrice,
    annualSelected: Boolean,
    onBillingChange: (annual: Boolean) -> Unit,
    onSubscribe: () -> Unit,
    features: List<String>,
    modifier: Modifier = Modifier,
    featuresTitle: String? = null,
    current: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Theme.shapes.medium)
            .background(Theme.colors.surface)
            .border(1.dp, Theme.colors.border, Theme.shapes.medium)
    ) {
        Column(Modifier.padding(Space * 4), verticalArrangement = Arrangement.spacedBy(Space * 4)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space)) {
                Text(name, style = Theme.typography.display)
                Text(pitch, color = Theme.colors.onBackgroundMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                listOf(false to monthly, true to annual).forEach { (isAnnual, price) ->
                    ChoiceTile(
                        selected = annualSelected == isAnnual,
                        onClick = { onBillingChange(isAnnual) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(price.price, style = Theme.typography.title)
                        Text(
                            price.period,
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                        if (price.note != null) {
                            Text(
                                price.note,
                                style = Theme.typography.caption,
                                color = Theme.colors.onPrimary,
                                modifier =
                                    Modifier.padding(top = Space)
                                        .clip(Theme.shapes.fullyRounded)
                                        .background(Theme.colors.primary)
                                        .padding(horizontal = Space * 2, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            Button(onClick = onSubscribe, enabled = !current, modifier = Modifier.fillMaxWidth()) {
                Text(if (current) "Your current plan" else "Get $name")
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Theme.colors.surfaceVariant).padding(Space * 4),
            verticalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            if (featuresTitle != null) Text(featuresTitle, style = Theme.typography.label)
            features.forEach { feature ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                    Icon(
                        Icons.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(16.dp),
                        tint = Theme.colors.primary,
                    )
                    Text(feature, color = Theme.colors.onSurfaceVariant)
                }
            }
        }
    }
}
