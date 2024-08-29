using System.Diagnostics;
using System.Net;

namespace RemoteManager
{
    public partial class Form1 : Form
    {
        private NotifyIcon trayIcon;
        private ContextMenuStrip trayMenu;
        private TextBox portTextBox;
        private Button saveButton;
        private HttpListener httpListener;
        private int port = 4433; // Default port
        private string appDataPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RemoteManager");
        private string portFilePath;
        private bool isServerRunning = false;
        private readonly object listenerLock = new object();
        private CancellationTokenSource cts = new CancellationTokenSource();

        public Form1()
        {
            InitializeComponent();
            CreateTrayIcon();
            InitializeForm();
            portFilePath = Path.Combine(appDataPath, "port.txt");
            LoadPortFromFile();
            StartWebServer();
        }

        private void InitializeForm()
        {
            this.Text = "Remote Manager";
            this.Size = new System.Drawing.Size(300, 150);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.StartPosition = FormStartPosition.CenterScreen;

            portTextBox = new TextBox
            {
                Text = port.ToString(),
                Dock = DockStyle.Top,
                Margin = new Padding(10),
                ReadOnly = false,
                Visible = true
            };

            saveButton = new Button
            {
                Text = "Save Port",
                Dock = DockStyle.Bottom,
                Margin = new Padding(10),
                Visible = true
            };
            saveButton.Click += SaveButton_Click;

            this.Controls.Add(portTextBox);
            this.Controls.Add(saveButton);
        }

        private void CreateTrayIcon()
        {
            trayMenu = new ContextMenuStrip();
            trayMenu.Items.Add("Exit", null, OnExit);

            trayIcon = new NotifyIcon
            {
                Icon = Properties.Resources.icon,
                ContextMenuStrip = trayMenu,
                Visible = true,
                Text = "Remote Manager"
            };

            trayIcon.MouseClick += TrayIcon_MouseClick;
        }

        private void TrayIcon_MouseClick(object sender, MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                ShowForm();
            }
        }

        private void ShowForm()
        {
            this.Show();
            this.WindowState = FormWindowState.Normal;
            this.BringToFront();
        }

        private void OnExit(object sender, EventArgs e)
        {
            try
            {
                StopWebServer();
                trayIcon.Visible = false;
                trayIcon.Dispose();
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Error during exit: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            finally
            {
                Application.Exit();
            }
        }

        private void SaveButton_Click(object sender, EventArgs e)
        {
            if (int.TryParse(portTextBox.Text, out int parsedPort) && parsedPort > 0 && parsedPort <= 65535)
            {
                if (port != parsedPort)
                {
                    port = parsedPort;
                    SavePortToFile();
                    RestartWebServer();
                }
                else
                {
                    MessageBox.Show("The port number has not changed.", "No Change", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            else
            {
                MessageBox.Show("Please enter a valid port number between 1 and 65535.", "Invalid Port", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void SavePortToFile()
        {
            try
            {
                Directory.CreateDirectory(appDataPath);
                File.WriteAllText(portFilePath, port.ToString());
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to save port to file: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void LoadPortFromFile()
        {
            if (File.Exists(portFilePath))
            {
                try
                {
                    string portText = File.ReadAllText(portFilePath);
                    if (int.TryParse(portText, out int parsedPort) && parsedPort > 0 && parsedPort <= 65535)
                    {
                        port = parsedPort;
                    }
                    else
                    {
                        port = 4433;
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Failed to load port from file: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    port = 4433;
                }
            }
            else
            {
                port = 4433;
            }
            portTextBox.Text = port.ToString();
        }

        private void StartWebServer()
        {
            lock (listenerLock)
            {
                if (isServerRunning)
                {
                    return;
                }

                StopWebServer();

                string url = $"http://0.0.0.0:{port}/";

                httpListener = new HttpListener();
                httpListener.Prefixes.Add(url);

                try
                {
                    httpListener.Start();
                    isServerRunning = true;

                    Task.Run(() =>
                    {
                        while (!cts.Token.IsCancellationRequested && httpListener != null && httpListener.IsListening)
                        {
                            try
                            {
                                var context = httpListener.GetContext();
                                var request = context.Request;
                                var response = context.Response;
                                string responseMessage = "Invalid Request";

                                if (request.HttpMethod == "POST")
                                {
                                    if (request.Url.AbsolutePath == "/api/reboot")
                                    {
                                        try
                                        {
                                            Process.Start("shutdown.exe", "/r /t 0");
                                            responseMessage = "Reboot initiated";
                                        }
                                        catch (Exception ex)
                                        {
                                            responseMessage = $"Error initiating reboot: {ex.Message}";
                                        }
                                    }
                                    else if (request.Url.AbsolutePath == "/api/shutdown")
                                    {
                                        try
                                        {
                                            Process.Start("shutdown.exe", "/s /t 0");
                                            responseMessage = "Shutdown initiated";
                                        }
                                        catch (Exception ex)
                                        {
                                            responseMessage = $"Error initiating shutdown: {ex.Message}";
                                        }
                                    }
                                    else if (request.Url.AbsolutePath == "/api/health")
                                    {
                                        responseMessage = "OK";
                                    }
                                }
                                else
                                {
                                    responseMessage = "Unsupported HTTP method";
                                }

                                byte[] buffer = System.Text.Encoding.UTF8.GetBytes(responseMessage);
                                response.ContentLength64 = buffer.Length;
                                using (var output = response.OutputStream)
                                {
                                    output.Write(buffer, 0, buffer.Length);
                                }
                            }
                            catch (HttpListenerException)
                            {
                                break;
                            }
                            catch (ObjectDisposedException)
                            {
                                break;
                            }
                            catch (Exception ex)
                            {
                                MessageBox.Show($"Unexpected error: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                            }
                        }
                    }, cts.Token);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Failed to start HTTP server: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }

        private void StopWebServer()
        {
            lock (listenerLock)
            {
                if (!isServerRunning || httpListener == null)
                {
                    return;
                }

                try
                {
                    cts.Cancel();
                    httpListener.Stop();
                    httpListener.Close();
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Failed to stop HTTP server: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                finally
                {
                    httpListener = null;
                    isServerRunning = false;
                    cts = new CancellationTokenSource();
                }
            }
        }

        private void RestartWebServer()
        {
            StopWebServer();
            StartWebServer();
        }

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            this.Hide();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                this.Hide();
            }
            else
            {
                base.OnFormClosing(e);
            }
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            StopWebServer();
            base.OnFormClosed(e);
        }
    }
}
