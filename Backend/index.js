const express = require('express');
const fs = require('fs-extra');
const path = require('path');
const { exec } = require('child_process');
const { app, BrowserWindow, Tray, Menu, globalShortcut, ipcMain } = require('electron');
const os = require("os");
const dns = require('dns').promises;

const PORT_FILE_PATH = path.join(__dirname, 'port.txt');
const DEFAULT_PORT = 4433;

let port = DEFAULT_PORT;
let server;
let tray;
Menu.setApplicationMenu(null);

function createWindow() {
  const win = new BrowserWindow({
    width: 300,
    height: 210,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      autoHideMenuBar: true
    },
    icon: path.join(__dirname, 'icon.png')
  });

app.setLoginItemSettings({
    openAtLogin: true    
})

  win.loadURL(`file://${__dirname}/index.html`);
  win.on('close', (e) => {
    if (tray) {
      e.preventDefault();
      win.hide();
    }
  });

  win.webContents.on('context-menu', (event) => {
    event.preventDefault();
  });

  globalShortcut.register('Ctrl+Q', () => {
    app.quit();
  });
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
      label: 'Exit',
      click: () => {
        stopServer(); // Stop the server first
        app.exit(); // Force the app to quit immediately
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

  server = app.listen(port, () => {
    console.log(`Server running on port ${port}`);
  });
}

function stopServer() {
  if (server) {
    server.close(() => {
      console.log('Server stopped');
    });
  }
}

function loadPortFromFile() {
  if (fs.existsSync(PORT_FILE_PATH)) {
    try {
      const portText = fs.readFileSync(PORT_FILE_PATH, 'utf8');
      const parsedPort = parseInt(portText, 10);
      if (parsedPort > 0 && parsedPort <= 65535) {
        port = parsedPort;
      } else {
        port = DEFAULT_PORT;
      }
    } catch (error) {
      console.error(`Failed to load port from file: ${error.message}`);
      port = DEFAULT_PORT;
    }
  } else {
    port = DEFAULT_PORT;
  }
}

function savePortToFile() {
  try {
    fs.ensureDirSync(__dirname);
    fs.writeFileSync(PORT_FILE_PATH, port.toString());
  } catch (error) {
    console.error(`Failed to save port to file: ${error.message}`);
  }
}

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
        localIP = net.address;
        return localIP; // Return immediately after finding the first valid IP
      }
    }
  }

  try {
    // Try to resolve google.com with a 1.5-second timeout
    await Promise.race([
      dns.resolve('google.com'),
      new Promise((_, reject) => setTimeout(() => reject('No Internet'), 1500))
    ]);
    return localIP; // Return the local IP if Google resolves
  } catch (error) {
    return 'No Internet'; // Return "No Internet" if it fails
  }
}

function setupIPC() {

  ipcMain.handle('get-port', () => port);
  ipcMain.handle('set-port', (event, newPort) => {
    if (Number.isInteger(newPort) && newPort > 0 && newPort <= 65535) {
      port = newPort;
      savePortToFile();
      stopServer();
      startServer();
    } else {
      throw new Error('Invalid port number');
    }
  });
  ipcMain.handle('get-ip-address', async () => {
    const ip = await getLocalIP();
    return ip;
  });
  
}

app.whenReady().then(() => {
  loadPortFromFile();
  createTray();
  createWindow();
  setupIPC();
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
