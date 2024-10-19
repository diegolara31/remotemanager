const express = require('express');
const fs = require('fs-extra');
const path = require('path');
const { exec } = require('child_process');
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain } = require('electron');
const os = require('os');
const dns = require('dns').promises;

// Determine the ProgramData directory
const programDataPath = process.env.PROGRAMDATA;

// Define the path for config.json inside ProgramData
const CONFIG_FILE_PATH = path.join(programDataPath, 'Remote Manager', 'config.json'); // Change 'MyApp' to your app's folder name

// Default configuration
const DEFAULT_CONFIG = {
  port: 4433,
  showOnBoot: true,
  autoStart: false,
};

let config = { ...DEFAULT_CONFIG };
let server;
let tray;

// Ensure the directory exists and has proper permissions
function ensureConfigDirectory() {
  try {
    const configDir = path.dirname(CONFIG_FILE_PATH);
    if (!fs.existsSync(configDir)) {
      fs.mkdirSync(configDir, { recursive: true });
    }
  } catch (error) {
    console.error(`Failed to create config directory: ${error.message}`);
  }
}

// Load config.json or create it with default settings if not found
function loadConfig() {
  ensureConfigDirectory();

  if (fs.existsSync(CONFIG_FILE_PATH)) {
    try {
      config = fs.readJsonSync(CONFIG_FILE_PATH);
    } catch (error) {
      console.error(`Failed to load config: ${error.message}`);
    }
  } else {
    saveConfig();
  }

  // Set the auto-start setting based on user preference
  app.setLoginItemSettings({
    openAtLogin: config.autoStart,
    path: app.getPath('exe'), // Get the current executable path
  });
}

// Save config.json with the current settings
function saveConfig() {
  try {
    fs.writeJsonSync(CONFIG_FILE_PATH, config);
  } catch (error) {
    console.error(`Failed to save config: ${error.message}`);
  }
}

Menu.setApplicationMenu(null);

function createWindow() {
  const win = new BrowserWindow({
    width: 350,
    height: 410,
    show: config.showOnBoot,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      autoHideMenuBar: true,
    },
    icon: path.join(__dirname, 'icon.png'),
  });

  win.loadURL(`file://${__dirname}/index.html`);

  win.on('close', (e) => {
    if (tray) {
      e.preventDefault();
      win.hide(); // Hide instead of closing
    }
  });

  win.webContents.on('context-menu', (event) => {
    event.preventDefault();
  });

  globalShortcut.register('Ctrl+Q', () => {
    app.quit();
  });

  return win;
}

function createTray() {
  tray = new Tray(path.join(__dirname, 'icon.png'));
  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Show',
      click: () => {
        const win = BrowserWindow.getAllWindows()[0];
        win.show();
      },
    },
    {
      label: 'Show on Boot',
      type: 'checkbox',
      checked: config.showOnBoot,
      click: () => {
        config.showOnBoot = !config.showOnBoot; // Toggle the value
        saveConfig(); // Save the updated config to file
      },
    },
    {
      label: 'Auto Start',
      type: 'checkbox',
      checked: config.autoStart,
      click: () => {
        config.autoStart = !config.autoStart; // Toggle the value
        saveConfig(); // Save the updated config
        app.setLoginItemSettings({
          openAtLogin: config.autoStart,
          path: app.getPath('exe'),
        });
      },
    },
    {
      label: 'Exit',
      click: () => {
        stopServer(); // Stop the server first
        app.exit(); // Quit the app
      },
    },
  ]);

  tray.setToolTip('Remote Manager');
  tray.setContextMenu(contextMenu);
}

function startServer() {
  const app = express();

  app.use(express.json());

  app.post('/api/reboot', (req, res) => {
    exec('shutdown -r -t 0', (error) => {
      if (error) {
        res.status(500).send(`Error initiating reboot: ${error.message}`);
      } else {
        res.send('Reboot initiated');
      }
    });
  });

  app.post('/api/shutdown', (req, res) => {
    exec('shutdown -s -t 0', (error) => {
      if (error) {
        res.status(500).send(`Error initiating shutdown: ${error.message}`);
      } else {
        res.send('Shutdown initiated');
      }
    });
  });

  app.post('/api/health', (req, res) => {
    res.send('OK');
  });

  app.use((req, res) => {
    res.status(405).send('Unsupported HTTP method');
  });

  server = app.listen(config.port, () => {
    console.log(`Server running on port ${config.port}`);
  });
}

function stopServer() {
  if (server) {
    server.close(() => {
      console.log('Server stopped');
    });
  }
}

// IPC Handlers
ipcMain.handle('get-port', () => config.port);
ipcMain.handle('set-port', (event, newPort) => {
  if (Number.isInteger(newPort) && newPort > 0 && newPort <= 65535) {
    config.port = newPort;
    saveConfig(); // Save the updated port to config.json
    stopServer(); // Restart the server with the new port
    startServer();
    return config.port;
  } else {
    throw new Error('Invalid port number');
  }
});

ipcMain.handle('get-ip-address', async () => {
  const ip = await getLocalIP();
  return ip;
});

async function getLocalIP() {
  const interfaces = os.networkInterfaces();
  let localIP = 'Not available';

  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      // Filter for IPv4, non-internal, and non-virtual interfaces
      if (
        net.family === 'IPv4' &&
        !net.internal &&
        !/VirtualBox/i.test(name) &&
        !/vmware/i.test(name) &&
        !/vbox/i.test(name) &&
        !/virtual/i.test(name)
      ) {
        try {
          await Promise.race([
            dns.resolve('google.com'),
            new Promise((_, reject) => setTimeout(() => reject('No Internet'), 1500))
          ]);
          localIP = net.address;
          return localIP; // Return the local IP if Google resolves
        } catch (error) {
          setTimeout(() => {
            stopServer();
            app.exit();
          }, 5000);
          return false; // Return "No Internet" if it fails
        }
      }
    }
  }
}

app.whenReady().then(() => {
  loadConfig(); // Load the config at startup
  createTray();
  const win = createWindow();
  startServer();

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit();
    }
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});
