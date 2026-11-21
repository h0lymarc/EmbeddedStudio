package com.h0lymarc.eside

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

// Foundation
import androidx.compose.foundation.layout.*

// Material 3
import androidx.compose.material3.*

// Material Icons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
// Compose Runtime
import androidx.compose.runtime.*

// UI
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

// Preview
import androidx.compose.ui.tooling.preview.Preview

// Coroutines
import kotlinx.coroutines.launch

import com.h0lymarc.eside.ui.theme.EmbeddedStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmbeddedStudioTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed
    )

    val scope = rememberCoroutineScope()

    var showMenu by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf("Home") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {

                Text(
                    text = "My Application",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp)
                )

                HorizontalDivider()

                NavigationDrawerItem(
                    label = { Text("Home") },
                    selected = selectedItem == "Home",
                    onClick = {
                        selectedItem = "Home"
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier.padding(
                        NavigationDrawerItemDefaults.ItemPadding
                    )
                )

                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = selectedItem == "Settings",
                    onClick = {
                        selectedItem = "Settings"
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier.padding(
                        NavigationDrawerItemDefaults.ItemPadding
                    )
                )

                NavigationDrawerItem(
                    label = { Text("About") },
                    selected = selectedItem == "About",
                    onClick = {
                        selectedItem = "About"
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier.padding(
                        NavigationDrawerItemDefaults.ItemPadding
                    )
                )
            }
        }
    ) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(selectedItem)
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    drawerState.open()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Open drawer"
                            )
                        }
                    },
                    actions = {

                        IconButton(onClick = {
                            // Search action
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }

                        Box {
                            IconButton(onClick = {
                                showMenu = true
                            }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = {
                                    showMenu = false
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        selectedItem = "Settings"
                                        showMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = {
                                        selectedItem = "About"
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Welcome to $selectedItem!",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
    }
}
