const express = require('express');
const fs = require('fs-extra');
const path = require('path');
const { exec } = require('child_process');
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain } = require('electron');
const os = require("os");
const dns = require('dns').promises

const CONFIG_FILE_PATH = path.join(__dirname, 'config.json');
const DEFAULT_CONFIG = {
  port: 4433,
  showOnBoot: true,
};

let config = { ...DEFAULT_CONFIG };
let server;
let tray;

// Load config.json or create it with default settings if not found
function loadConfig() {
  if (fs.existsSync(CONFIG_FILE_PATH)) {
    try {
      config = fs.readJsonSync(CONFIG_FILE_PATH);
    } catch (error) {
      console.error(`Failed to load config: ${error.message}`);
    }
  } else {
    saveConfig();
  }
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
    width: 330,
    height: 260,
    show: config.showOnBoot, // Use the showOnBoot setting to decide if it should be shown
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
      checked: config.showOnBoot, // Reflect the current setting
      click: () => {
        config.showOnBoot = !config.showOnBoot; // Toggle the value
        saveConfig(); // Save the updated config to file
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
        !/VirtualBox/i.test(name) &&   // Exclude VirtualBox interfaces
        !/vmware/i.test(name) &&       // Exclude VMware interfaces
        !/vbox/i.test(name) &&         // Exclude other VirtualBox-related interfaces
        !/virtual/i.test(name)         // General check to exclude virtual adapters
      ) {
        try {
          // Try to resolve google.com with a 1.5-second timeout
          await Promise.race([
            dns.resolve('google.com'),
            new Promise((_, reject) => setTimeout(() => reject('No Internet'), 1500))
          ]);
          localIP = net.address;
          return localIP; // Return the local IP if Google resolves
        } catch (error) {
          setTimeout(() => {
            stopServer();
            app.exit()
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
