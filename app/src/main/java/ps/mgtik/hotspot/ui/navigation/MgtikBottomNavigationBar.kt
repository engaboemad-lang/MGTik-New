package ps.mgtik.hotspot.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * MgtikBottomNavigationBar - شريط التنقل السفلي الموحد (§Navigation).
 * 1. قائمة bottomNavItems في Screen.kt هي المصدر الوحيد للعناصر (SSOT).
 * 2. التعامل الصحيح مع الـ BackStack لمنع تكرار الوجهات.
 * 3. أيقونة ممتلئة للتبويب المحدد ومخطّطة لغير المحدد، مع مؤشر tonal وتسمية أعرض للمحدد
 *    ليظهر الاختيار بوضوح دون الاعتماد على اللون وحده.
 * 4. التسمية سطر واحد بلا قص؛ والأيقونة تحمل contentDescription = null لأن التسمية تكفي قارئ الشاشة.
 */
@Composable
fun MgtikBottomNavigationBar(
    navController: NavController,
    currentRoute: String?
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp
    ) {
        Screen.bottomNavItems.forEach { screen ->
            val title = screen.titleRes?.let { stringResource(it) } ?: screen.titleAr
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (selected) screen.icon else (screen.iconOutlined ?: screen.icon),
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                alwaysShowLabel = true,
                selected = selected,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                onClick = {
                    if (!selected) {
                        navController.navigate(screen.route) {
                            // نقطة الرجوع الثابتة هي «نظرة عامة»: وجهة البدء الفعلية (Splash) تُزال من المكدّس بعد الإقلاع،
                            // فالـ popUpTo عليها لا يفعل شيئاً ويتراكم المكدّس مع كل تبديل تبويب.
                            popUpTo(Screen.Overview.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}
