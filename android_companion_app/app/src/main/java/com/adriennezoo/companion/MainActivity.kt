package com.adriennezoo.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.adriennezoo.companion.ui.screens.ActivateAnimalScreen
import com.adriennezoo.companion.ui.screens.AnimalProfileScreen
import com.adriennezoo.companion.ui.screens.JourneyMapScreen
import com.adriennezoo.companion.ui.screens.MyAnimalsScreen
import com.adriennezoo.companion.ui.theme.AdrienneZooTheme
import com.adriennezoo.companion.ui.theme.LinenCanvas
import com.adriennezoo.companion.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdrienneZooTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LinenCanvas
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "my_animals"
    ) {
        composable("my_animals") {
            MyAnimalsScreen(
                viewModel = viewModel,
                onNavigateToActivate = { navController.navigate("activate") },
                onNavigateToAnimal = { animalId ->
                    navController.navigate("profile/${java.net.URLEncoder.encode(animalId, "UTF-8")}")
                }
            )
        }

        composable("activate") {
            ActivateAnimalScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAnimal = { animalId ->
                    navController.navigate("profile/${java.net.URLEncoder.encode(animalId, "UTF-8")}") {
                        popUpTo("my_animals")
                    }
                }
            )
        }

        composable(
            route = "profile/{animalId}",
            arguments = listOf(navArgument("animalId") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedId = backStackEntry.arguments?.getString("animalId") ?: ""
            val animalId = java.net.URLDecoder.decode(encodedId, "UTF-8")
            AnimalProfileScreen(
                animalId = animalId,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenFullMap = { id ->
                    navController.navigate("map/${java.net.URLEncoder.encode(id, "UTF-8")}")
                }
            )
        }

        composable(
            route = "map/{animalId}",
            arguments = listOf(navArgument("animalId") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedId = backStackEntry.arguments?.getString("animalId") ?: ""
            val animalId = java.net.URLDecoder.decode(encodedId, "UTF-8")
            JourneyMapScreen(
                animalId = animalId,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
