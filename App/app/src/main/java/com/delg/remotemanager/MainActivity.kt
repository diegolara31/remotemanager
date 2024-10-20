package com.delg.remotemanager

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SavedIp(
    val address: String,
    var customName: String = address, // Default to address if no custom name is set
    var isPinned: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteManagerApp(application = application)
        }
    }
}

@Composable
fun RemoteManagerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

class ThemeViewModel(application: Application) : ViewModel() {
    private val sharedPreferences: SharedPreferences = application.getSharedPreferences("RemoteManager", Context.MODE_PRIVATE)
    private val _isDarkTheme = MutableStateFlow(loadInitialTheme())
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme

    private fun loadInitialTheme(): Boolean {
        // Load the saved theme preference or default to dark mode
        return sharedPreferences.getBoolean("dark_theme", true) // true means dark mode is the default
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val newTheme = !_isDarkTheme.value
            _isDarkTheme.value = newTheme
            sharedPreferences.edit().putBoolean("dark_theme", newTheme).apply()
        }
    }
}

@Composable
fun RemoteManagerApp(
    application: Application,
    themeViewModel: ThemeViewModel = viewModel(factory = ThemeViewModelFactory(application)),
    remoteManagerViewModel: RemoteManagerViewModel = viewModel()
) {
    val navController = rememberNavController()
    val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()

    RemoteManagerTheme(darkTheme = isDarkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                NavHost(navController = navController, startDestination = "ipSelection") {
                    composable("ipSelection") {
                        IpSelectionScreen(
                            onSelectIp = { ip ->
                                remoteManagerViewModel.setIpPort(ip)
                                navController.navigate("controlPanel")
                            },
                            onNewIp = {
                                navController.navigate("ipInput")
                            },
                            viewModel = remoteManagerViewModel
                        )
                    }
                    composable("ipInput") {
                        IPInputScreen(
                            onSubmit = { ip ->
                                remoteManagerViewModel.setIpPort(ip)
                                navController.navigate("controlPanel")
                            },
                            viewModel = remoteManagerViewModel
                        )
                    }
                    composable("controlPanel") {
                        ControlPanelScreen(remoteManagerViewModel)
                    }
                }
            }

            // Theme toggle button
            IconButton(
                onClick = { themeViewModel.toggleTheme() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle theme"
                )
            }
        }
    }
}

@Composable
fun IpSelectionScreen(onSelectIp: (String) -> Unit, onNewIp: () -> Unit, viewModel: RemoteManagerViewModel) {
    val savedIps by viewModel.savedIps.collectAsState()
    var isLoading by remember { mutableStateOf(false) }
    var editingIp by remember { mutableStateOf<String?>(null) }
    var editedIpValue by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (savedIps.isNotEmpty()) {
                Text("Saved IPs:")
                Spacer(modifier = Modifier.height(16.dp))
                savedIps.forEach { savedIp ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editingIp == savedIp.address) {
                            TextField(
                                value = editedIpValue,
                                onValueChange = { editedIpValue = it },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                viewModel.updateIp(savedIp.address, editedIpValue)
                                editingIp = null
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Save edit")
                            }
                        } else {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isLoading = true
                                        val result = viewModel.testConnection(savedIp.address)
                                        isLoading = false
                                        if (result == "Connection successful") {
                                            onSelectIp(savedIp.address)
                                        } else {
                                            Toast.makeText(viewModel.getApplication(), "Error: $result", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isLoading
                            ) {
                                Text(savedIp.address)
                            }
                            IconButton(onClick = { viewModel.togglePinIp(savedIp.address) }) {
                                Icon(
                                    if (savedIp.isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Toggle pin"
                                )
                            }
                            IconButton(onClick = {
                                editingIp = savedIp.address
                                editedIpValue = savedIp.address
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.deleteIp(savedIp.address) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Or enter a new IP:")
            Button(
                onClick = onNewIp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("New IP")
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
            }
        }

        // Footer
        Text(
            text = "Made with ❤️ by DELG",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }


}

@Composable
fun IPInputScreen(onSubmit: (String) -> Unit, viewModel: RemoteManagerViewModel) {
    var ipPort by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(bottom = 48.dp), // Adjust bottom padding to make room for footer
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
                            withContext(Dispatchers.Main) {
                                Toast.makeText(viewModel.getApplication(), result, Toast.LENGTH_SHORT).show()
                            }
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

        // Footer
        Text(
            text = "Made with ❤️ by DELG",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
fun ControlPanelScreen(viewModel: RemoteManagerViewModel) {
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(bottom = 48.dp), // Adjust bottom padding to make room for footer
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

        // Footer
        Text(
            text = "Made with ❤️ by DELG",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

class RemoteManagerViewModel(application: Application) : androidx.lifecycle.AndroidViewModel(application) {
    private var _ipPort by mutableStateOf("")
    private val sharedPreferences: SharedPreferences = application.getSharedPreferences("RemoteManager", Context.MODE_PRIVATE)
    private val _savedIps = MutableStateFlow<List<SavedIp>>(emptyList())
    val savedIps: StateFlow<List<SavedIp>> = _savedIps

    private val gson = Gson() // Initialize Gson

    init {
        loadSavedIps()
    }

    fun setIpPort(value: String) {
        _ipPort = value
        if (value != "demo") {
            saveIp(value, _ipPort)
        }
    }

    private fun loadSavedIps() {
        val json = sharedPreferences.getString("saved_ips", null) // Load the JSON string
        val type = object : TypeToken<List<SavedIp>>() {}.type
        _savedIps.value = if (json != null) {
            gson.fromJson(json, type) // Deserialize JSON to List<SavedIp>
        } else {
            emptyList()
        }
    }

    private fun saveIp(ip: String, customName: String) {
        if (ip == "demo" || ip == "demo:demo") {
            return // Skip saving demo credentials
        } else {
            val currentIps = _savedIps.value.toMutableList()
            if (!currentIps.any { it.address == ip }) {
                currentIps.add(SavedIp(ip, customName))
                updateSavedIps(currentIps)
            }
        }
    }

    fun updateCustomName(ip: String, newName: String) {
        val updatedIps = _savedIps.value.map {
            if (it.address == ip) it.copy(customName = newName) else it
        }
        updateSavedIps(updatedIps)
    }

    private fun updateSavedIps(ips: List<SavedIp>) {
        _savedIps.value = ips.sortedByDescending { it.isPinned }
        val json = gson.toJson(ips) // Serialize List<SavedIp> to JSON
        sharedPreferences.edit().putString("saved_ips", json).apply() // Save JSON string
    }

    fun togglePinIp(ip: String) {
        val updatedIps = _savedIps.value.map {
            if (it.address == ip) it.copy(isPinned = !it.isPinned) else it
        }
        updateSavedIps(updatedIps)
    }

    fun deleteIp(ip: String) {
        val updatedIps = _savedIps.value.filter { it.address != ip }
        updateSavedIps(updatedIps)
    }

    fun updateIp(oldIp: String, newIp: String) {
        if (oldIp != newIp) {
            val updatedIps = _savedIps.value.map {
                if (it.address == oldIp) it.copy(address = newIp) else it
            }
            updateSavedIps(updatedIps)
        }
    }

    suspend fun testConnection(ipPort: String): String {
        return withContext(Dispatchers.IO) {
            // Check if it's a demo mode with "demo" or "demo:demo"
            if (ipPort == "demo" || ipPort == "demo:demo") {
                delay(1000) // Simulate network delay
                return@withContext "Connection successful"
            }

            var connection: HttpURLConnection? = null
            try {
                val url = URL("http://$ipPort/api/health")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    "Connection successful"
                } else {
                    "Check the IP and Port matches the Windows client"
                }
            } catch (e: Exception) {
                "Check the IP and Port matches the Windows client"
            } finally {
                connection?.disconnect()
            }
        }
    }

    suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            // Check if it's a demo mode with "demo" or "demo:demo"
            if (_ipPort == "demo" || _ipPort == "demo:demo") {
                delay(1000) // Simulate network delay
                return@withContext "Command '$command' sent successfully (Demo Mode)"
            }

            var connection: HttpURLConnection? = null
            try {
                val url = URL("http://$_ipPort/api/$command")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    "Command sent successfully"
                } else {
                    "Check the IP and Port matches the Windows client"
                }
            } catch (e: Exception) {
                "Check the IP and Port matches the Windows client"
            } finally {
                connection?.disconnect()
            }
        }
    }

}

class ThemeViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ThemeViewModel::class.java)) {
            return ThemeViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
