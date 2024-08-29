package com.delg.remotemanager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RemoteManagerApp()
                }
            }
        }
    }
}

@Composable
fun RemoteManagerApp(viewModel: RemoteManagerViewModel = viewModel()) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "ipInput") {
        composable("ipInput") {
            IPInputScreen(
                onSubmit = { ip ->
                    viewModel.setIpPort(ip)
                    navController.navigate("controlPanel")
                },
                viewModel = viewModel
            )
        }
        composable("controlPanel") {
            ControlPanelScreen(viewModel)
        }
    }
}

@Composable
fun IPInputScreen(onSubmit: (String) -> Unit, viewModel: RemoteManagerViewModel) {
    var ipPort by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextField(
            value = ipPort,
            onValueChange = { ipPort = it },
            label = { Text("Enter IP:PORT") }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                coroutineScope.launch {
                    isLoading = true
                    val result = viewModel.testConnection(ipPort)
                    isLoading = false
                    if (result == "Connection successful") {
                        onSubmit(ipPort)
                    } else {
                        Toast.makeText(viewModel.getApplication(), result, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text("Submit")
            }
        }
    }
}
@Composable
fun ControlPanelScreen(viewModel: RemoteManagerViewModel) {
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                coroutineScope.launch {
                    val result = viewModel.sendCommand("reboot")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(viewModel.getApplication(), result, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reboot")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                coroutineScope.launch {
                    val result = viewModel.sendCommand("shutdown")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(viewModel.getApplication(), result, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Shutdown")
        }
    }
}

class RemoteManagerViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private var _ipPort by mutableStateOf("")

    fun setIpPort(value: String) {
        _ipPort = value
    }

    suspend fun testConnection(ipPort: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ipPort/api/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    "Connection successful"
                } else {
                    "Error: Response code $responseCode"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$_ipPort/api/$command")  // Use the renamed property
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    "Command sent successfully"
                } else {
                    "Error: Response code $responseCode"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }
}
